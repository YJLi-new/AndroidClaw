package ai.androidclaw.feature.skills

import ai.androidclaw.runtime.skills.SkillConfigurationSnapshot
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSnapshot
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

internal suspend fun SkillManager.readConfigurationCompat(
    skill: SkillSnapshot,
): SkillConfigurationSnapshot {
    val method = findSuspendMethod(name = "readConfiguration", valueParameterCount = 1)
        ?: findSuspendMethod(name = "readSkillConfiguration", valueParameterCount = 1)
        ?: error("SkillManager does not expose a configuration read API.")
    return invokeSuspendMethod(method, skill)
}

internal suspend fun SkillManager.saveConfigurationCompat(
    skillKey: String,
    secretUpdates: Map<String, String>,
    clearedSecrets: Set<String>,
    configUpdates: Map<String, String?>,
) {
    val modernMethod = findSuspendMethod(name = "saveConfiguration", valueParameterCount = 4)
    if (modernMethod != null) {
        invokeSuspendMethod<Unit>(
            modernMethod,
            skillKey,
            secretUpdates,
            clearedSecrets,
            configUpdates,
        )
        return
    }

    val legacyMethod = findSuspendMethod(name = "saveSkillConfiguration", valueParameterCount = 3)
        ?: error("SkillManager does not expose a configuration save API.")
    invokeSuspendMethod<Unit>(
        legacyMethod,
        skillKey,
        secretUpdates + clearedSecrets.associateWith { null },
        configUpdates,
    )
}

private fun SkillManager.findSuspendMethod(
    name: String,
    valueParameterCount: Int,
): Method? {
    return javaClass.methods.firstOrNull { method ->
        method.name == name &&
            method.parameterTypes.size == valueParameterCount + 1 &&
            Continuation::class.java.isAssignableFrom(method.parameterTypes.last())
    }
}

@Suppress("UNCHECKED_CAST")
private suspend fun <T> SkillManager.invokeSuspendMethod(
    method: Method,
    vararg args: Any?,
): T {
    return suspendCoroutineUninterceptedOrReturn { continuation ->
        try {
            val result = method.invoke(this, *args, continuation)
            if (result === COROUTINE_SUSPENDED) {
                COROUTINE_SUSPENDED
            } else {
                result as T
            }
        } catch (error: InvocationTargetException) {
            throw (error.targetException ?: error)
        }
    }
}
