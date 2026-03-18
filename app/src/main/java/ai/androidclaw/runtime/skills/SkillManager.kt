package ai.androidclaw.runtime.skills

import ai.androidclaw.data.SkillConfigStore
import ai.androidclaw.data.SkillSecretStore
import ai.androidclaw.data.model.SkillRecord
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.tools.ToolAvailabilityStatus
import ai.androidclaw.runtime.tools.ToolDescriptor
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

class SkillManager(
    private val skillSourceScanner: SkillSourceScanner,
    private val localSkillImporter: LocalSkillImporter,
    private val skillRepository: SkillRepository,
    private val toolDescriptor: (String) -> ToolDescriptor?,
    private val skillConfigStore: SkillConfigStore,
    private val skillSecretStore: SkillSecretStore,
) {
    private val cacheMutex = Mutex()
    private var globalSourceSynced: Boolean = false
    private val workspaceSourceSynced = mutableSetOf<String>()
    private var cachedGlobalInventory: List<SkillSnapshot>? = null
    private val cachedInventoryBySession = mutableMapOf<String?, List<SkillSnapshot>>()
    private val cachedEffectiveSkillsBySession = mutableMapOf<String?, List<SkillSnapshot>>()

    suspend fun refreshBundledSkills(forceRefresh: Boolean = false): List<SkillSnapshot> = refreshSkillInventory(forceRefresh = forceRefresh)

    suspend fun refreshSkillInventory(
        sessionId: String? = null,
        forceRefresh: Boolean = false,
    ): List<SkillSnapshot> =
        cacheMutex.withLock {
            ensureGlobalSources(forceRefresh = forceRefresh)
            ensureWorkspaceSource(
                sessionId = sessionId,
                forceRefresh = forceRefresh,
            )
            val inventory = loadResolvedInventory(sessionId = sessionId)
            cachedInventoryBySession[sessionId] = inventory
            if (sessionId == null) {
                cachedGlobalInventory = inventory
            }
            inventory
        }

    suspend fun refreshSkills(
        sessionId: String? = null,
        forceRefresh: Boolean = false,
    ): List<SkillSnapshot> =
        cacheMutex.withLock {
            ensureGlobalSources(forceRefresh = forceRefresh)
            ensureWorkspaceSource(
                sessionId = sessionId,
                forceRefresh = forceRefresh,
            )
            val effectiveSkills =
                loadResolvedInventory(sessionId = sessionId)
                    .filter { skill ->
                        skill.resolutionState == SkillResolutionState.Effective &&
                            skill.enabled &&
                            skill.eligibility.status == SkillEligibilityStatus.Eligible
                    }
            cachedEffectiveSkillsBySession[sessionId] = effectiveSkills
            effectiveSkills
        }

    suspend fun setEnabled(
        skillId: String,
        enabled: Boolean,
    ) {
        skillRepository.setEnabled(skillId, enabled)
        invalidateCaches()
    }

    suspend fun importLocalSkills(uri: Uri): SkillImportResult {
        val result = localSkillImporter.importZip(uri)
        cacheMutex.withLock {
            syncLocalSource()
            invalidateCachesLocked()
        }
        return result
    }

    suspend fun readSkillConfiguration(skill: SkillSnapshot): SkillConfigurationSnapshot {
        val frontmatter =
            skill.frontmatter ?: return SkillConfigurationSnapshot(
                skillId = skill.id,
                skillKey = skill.skillKey,
                displayName = skill.displayName,
            )
        val recoveredSecrets = linkedSetOf<String>()
        val secretFields =
            frontmatter.declaredSecretNames().map { secretName ->
                val configured = !skillSecretStore.readSecret(skill.skillKey, secretName).isNullOrBlank()
                if (skillSecretStore.consumeRecoveryNotice(skill.skillKey, secretName)) {
                    recoveredSecrets += secretName
                }
                SkillSecretField(
                    envName = secretName,
                    configured = configured,
                )
            }
        val configFields =
            frontmatter.declaredConfigPaths().map { configPath ->
                SkillConfigField(
                    path = configPath,
                    value = skillConfigStore.readConfig(skill.skillKey, configPath),
                )
            }
        return SkillConfigurationSnapshot(
            skillId = skill.id,
            skillKey = skill.skillKey,
            displayName = skill.displayName,
            secretFields = secretFields,
            configFields = configFields,
            recoveryMessage =
                when (recoveredSecrets.size) {
                    0 -> null
                    1 -> "Saved secret ${recoveredSecrets.single()} could not be restored on this device. Please enter it again."
                    else -> "Some saved secrets could not be restored on this device. Please enter them again."
                },
        )
    }

    suspend fun readConfiguration(skill: SkillSnapshot): SkillConfigurationSnapshot = readSkillConfiguration(skill)

    suspend fun saveSkillConfiguration(
        skillKey: String,
        secretUpdates: Map<String, String?>,
        configUpdates: Map<String, String?>,
    ) {
        secretUpdates.forEach { (envName, value) ->
            skillSecretStore.writeSecret(skillKey, envName, value)
        }
        configUpdates.forEach { (configPath, value) ->
            skillConfigStore.writeConfig(skillKey, configPath, value)
        }
        invalidateCaches()
    }

    suspend fun saveConfiguration(
        skillKey: String,
        secretUpdates: Map<String, String>,
        clearedSecrets: Set<String>,
        configUpdates: Map<String, String?>,
    ) {
        saveSkillConfiguration(
            skillKey = skillKey,
            secretUpdates =
                buildMap {
                    putAll(secretUpdates)
                    clearedSecrets.forEach { envName -> put(envName, null) }
                },
            configUpdates = configUpdates,
        )
    }

    fun selectModelSkills(skills: List<SkillSnapshot>): List<SkillSnapshot> {
        return skills.filter { skill ->
            val frontmatter = skill.frontmatter ?: return@filter false
            skill.enabled &&
                skill.eligibility.status == SkillEligibilityStatus.Eligible &&
                !frontmatter.disableModelInvocation &&
                frontmatter.commandDispatch == SkillCommandDispatch.Model
        }
    }

    fun findSlashSkill(
        commandName: String,
        skills: List<SkillSnapshot>,
    ): SkillSnapshot? {
        return skills.firstOrNull { skill ->
            val frontmatter = skill.frontmatter ?: return@firstOrNull false
            skill.enabled &&
                frontmatter.userInvocable &&
                frontmatter.name == commandName
        }
    }

    suspend fun invalidateCaches() {
        cacheMutex.withLock {
            invalidateCachesLocked()
        }
    }

    private suspend fun loadResolvedInventory(sessionId: String?): List<SkillSnapshot> {
        val allRecords = skillRepository.getAllSkills()
        val relevantRecords =
            allRecords.filter { record ->
                when (record.sourceType) {
                    SkillSourceType.Bundled,
                    SkillSourceType.Local,
                    -> true
                    SkillSourceType.Workspace -> record.workspaceSessionId == sessionId
                }
            }
        val evaluated =
            relevantRecords
                .map { it.toSnapshot() }
                .map { skill -> applyEligibility(skill) }

        return evaluated
            .groupBy { skill -> skill.skillKey }
            .values
            .flatMap(::resolvePrecedence)
            .sortedWith(
                compareBy<SkillSnapshot> { it.displayName }
                    .thenBy { sourcePriority(it.sourceType) }
                    .thenBy { it.workspaceSessionId.orEmpty() }
                    .thenBy { it.id },
            )
    }

    private fun resolvePrecedence(skills: List<SkillSnapshot>): List<SkillSnapshot> {
        val sorted =
            skills.sortedWith(
                compareBy<SkillSnapshot> { precedenceWinnerRank(it) }
                    .thenBy { sourcePriority(it.sourceType) }
                    .thenBy { it.id },
            )
        val winner = sorted.firstOrNull()
        return sorted.mapIndexed { index, skill ->
            if (index == 0 || winner == null) {
                skill.copy(
                    resolutionState = SkillResolutionState.Effective,
                    shadowedBy = null,
                )
            } else {
                skill.copy(
                    resolutionState = SkillResolutionState.Shadowed,
                    shadowedBy = winner.sourceSummary(),
                )
            }
        }
    }

    private fun precedenceWinnerRank(skill: SkillSnapshot): Int =
        when {
            skill.enabled && skill.eligibility.status == SkillEligibilityStatus.Eligible -> 0
            skill.enabled -> 1
            else -> 2
        }

    private suspend fun ensureGlobalSources(forceRefresh: Boolean) {
        if (forceRefresh ||
            !globalSourceSynced ||
            skillRepository.getAllSkills().none {
                it.sourceType == SkillSourceType.Bundled || it.sourceType == SkillSourceType.Local
            }
        ) {
            syncBundledSource()
            syncLocalSource()
            globalSourceSynced = true
        }
    }

    private suspend fun ensureWorkspaceSource(
        sessionId: String?,
        forceRefresh: Boolean,
    ) {
        if (sessionId.isNullOrBlank()) return
        if (forceRefresh || sessionId !in workspaceSourceSynced) {
            syncWorkspaceSource(sessionId)
            workspaceSourceSynced += sessionId
        }
    }

    private suspend fun syncBundledSource() {
        syncSourceRecords(
            sourceType = SkillSourceType.Bundled,
            workspaceSessionId = null,
            skills = skillSourceScanner.scanBundled(),
        )
    }

    private suspend fun syncLocalSource() {
        syncSourceRecords(
            sourceType = SkillSourceType.Local,
            workspaceSessionId = null,
            skills = skillSourceScanner.scanLocal(),
        )
    }

    private suspend fun syncWorkspaceSource(sessionId: String) {
        syncSourceRecords(
            sourceType = SkillSourceType.Workspace,
            workspaceSessionId = sessionId,
            skills = skillSourceScanner.scanWorkspace(sessionId),
        )
    }

    private suspend fun syncSourceRecords(
        sourceType: SkillSourceType,
        workspaceSessionId: String?,
        skills: List<SkillSnapshot>,
    ) {
        val allRecords = skillRepository.getAllSkills()
        val existingRecords =
            allRecords.filter { record ->
                record.sourceType == sourceType && record.workspaceSessionId == workspaceSessionId
            }
        val existingById = existingRecords.associateBy(SkillRecord::id)
        skillRepository.upsertAll(
            skills.map { skill ->
                skill.toRecord(existingById[skill.id])
            },
        )
        val validIds = skills.map(SkillSnapshot::id).toSet()
        existingRecords
            .filter { record -> record.id !in validIds }
            .forEach { staleRecord ->
                skillRepository.deleteSkill(staleRecord.id)
            }
    }

    private suspend fun applyEligibility(skill: SkillSnapshot): SkillSnapshot {
        val frontmatter = skill.frontmatter ?: return skill
        val secretStatuses =
            frontmatter.declaredSecretNames().associateWith { secretName ->
                !skillSecretStore.readSecret(skill.skillKey, secretName).isNullOrBlank()
            }
        val configStatuses =
            frontmatter.declaredConfigPaths().associateWith { configPath ->
                !skillConfigStore.readConfig(skill.skillKey, configPath).isNullOrBlank()
            }

        val android = frontmatter.androidMetadata()
        val bridgeOnly = android?.get("bridgeOnly")?.jsonPrimitive?.booleanOrNull == true
        if (bridgeOnly) {
            return skill.copy(
                eligibility =
                    SkillEligibility(
                        status = SkillEligibilityStatus.BridgeOnly,
                        reasons = listOf("This skill is marked bridgeOnly and is not runnable locally."),
                    ),
                secretStatuses = secretStatuses,
                configStatuses = configStatuses,
            )
        }

        val eligibilityReasons = mutableListOf<String>()
        val unsupportedBins = (frontmatter.requiredBins() + frontmatter.requiredAnyBins()).distinct()
        if (unsupportedBins.isNotEmpty()) {
            eligibilityReasons += "Unsupported on Android: requires.bins ${unsupportedBins.joinToString()}"
        }

        eligibilityReasons +=
            frontmatter.requiredEnvNames().mapNotNull { requiredEnv ->
                if (secretStatuses[requiredEnv] == true) {
                    null
                } else {
                    "Missing env requirement: $requiredEnv"
                }
            }

        eligibilityReasons +=
            frontmatter.requiredConfigPaths().mapNotNull { requiredConfig ->
                if (configStatuses[requiredConfig] == true) {
                    null
                } else {
                    "Missing config requirement: $requiredConfig"
                }
            }

        val requiredTools = mutableSetOf<String>()
        frontmatter.commandTool?.let(requiredTools::add)
        android
            ?.get("requiresTools")
            ?.let { element ->
                runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())
            }?.let(requiredTools::addAll)
        eligibilityReasons +=
            requiredTools.mapNotNull { requiredTool ->
                val descriptor = toolDescriptor(requiredTool)
                when {
                    descriptor == null -> "Missing tool: $requiredTool"
                    descriptor.availability.status == ToolAvailabilityStatus.Available -> null
                    else -> descriptor.toEligibilityReason()
                }
            }

        val status =
            when {
                eligibilityReasons.isEmpty() -> SkillEligibilityStatus.Eligible
                unsupportedBins.isNotEmpty() -> SkillEligibilityStatus.BridgeOnly
                else -> SkillEligibilityStatus.MissingTool
            }
        return skill.copy(
            eligibility =
                SkillEligibility(
                    status = status,
                    reasons = eligibilityReasons,
                ),
            secretStatuses = secretStatuses,
            configStatuses = configStatuses,
        )
    }

    private fun invalidateCachesLocked() {
        cachedGlobalInventory = null
        cachedInventoryBySession.clear()
        cachedEffectiveSkillsBySession.clear()
        globalSourceSynced = false
        workspaceSourceSynced.clear()
    }
}

private fun SkillSnapshot.toRecord(existing: SkillRecord?): SkillRecord {
    val now = Instant.now()
    return SkillRecord(
        id = id,
        skillKey = skillKey,
        sourceType = sourceType,
        workspaceSessionId = workspaceSessionId,
        baseDir = baseDir,
        enabled = existing?.enabled ?: enabled,
        displayName = displayName,
        description = frontmatter?.description.orEmpty(),
        frontmatter = frontmatter,
        instructionsMd = instructionsMd,
        eligibilityStatus = eligibility.status,
        eligibilityReasons = eligibility.reasons,
        parseError = parseError,
        importedAt = existing?.importedAt ?: importedAtFor(sourceType, now),
        updatedAt = now,
    )
}

private fun SkillRecord.toSnapshot(): SkillSnapshot =
    SkillSnapshot(
        id = id,
        skillKey = skillKey,
        sourceType = sourceType,
        workspaceSessionId = workspaceSessionId,
        baseDir = baseDir,
        enabled = enabled,
        frontmatter = frontmatter,
        instructionsMd = instructionsMd,
        eligibility =
            SkillEligibility(
                status = eligibilityStatus,
                reasons = eligibilityReasons,
            ),
        parseError = parseError,
    )

private fun importedAtFor(
    sourceType: SkillSourceType,
    now: Instant,
): Instant? =
    when (sourceType) {
        SkillSourceType.Bundled -> null
        SkillSourceType.Local,
        SkillSourceType.Workspace,
        -> now
    }

private fun sourcePriority(sourceType: SkillSourceType): Int =
    when (sourceType) {
        SkillSourceType.Workspace -> 0
        SkillSourceType.Local -> 1
        SkillSourceType.Bundled -> 2
    }

private fun SkillSnapshot.sourceSummary(): String =
    when (sourceType) {
        SkillSourceType.Bundled -> "bundled"
        SkillSourceType.Local -> "local"
        SkillSourceType.Workspace -> "workspace:${workspaceSessionId.orEmpty()}"
    }

private fun ToolDescriptor.toEligibilityReason(): String =
    when (availability.status) {
        ToolAvailabilityStatus.Available -> "Tool available: $name"
        ToolAvailabilityStatus.Unavailable -> "Tool blocked: $name (${availability.reason ?: "unavailable"})"
        ToolAvailabilityStatus.PermissionRequired -> {
            val permissionSummary =
                requiredPermissions
                    .map { it.displayName }
                    .ifEmpty { listOf("permission required") }
                    .joinToString()
            "Tool blocked: $name ($permissionSummary)"
        }
        ToolAvailabilityStatus.ForegroundRequired -> {
            "Tool blocked: $name (${availability.reason ?: "foreground required"})"
        }
        ToolAvailabilityStatus.DisabledByConfig -> {
            "Tool blocked: $name (${availability.reason ?: "disabled by config"})"
        }
    }
