package ai.androidclaw.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val ClawPageBackground = Color(0xFFF8F8F5)
val ClawCardBackground = Color(0xFFFFFFFF)
val ClawGreen = Color(0xFF64C914)
val ClawGreenMuted = Color(0xFFE9F7DE)
val ClawInk = Color(0xFF111B31)
val ClawInkMuted = Color(0xFF647086)
val ClawBorder = Color(0xFFE2E5EA)
val ClawBlueSoft = Color(0xFFEFF5FF)
val ClawMintSoft = Color(0xFFEFFAF3)

@Composable
fun ClawPage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .background(ClawPageBackground),
    ) {
        content()
    }
}

@Composable
fun ClawScreenHeader(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    titleTestTag: String,
    modifier: Modifier = Modifier,
    iconBackground: Color = Color.Transparent,
    iconTint: Color? = ClawGreen,
    trailing: @Composable RowScope.() -> Unit = {
        Text(
            text = "...",
            color = ClawInk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    },
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint ?: Color.Unspecified,
                modifier = Modifier.size(34.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                modifier =
                    Modifier
                        .semantics { heading() }
                        .testTag(titleTestTag),
                color = ClawInk,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = ClawInkMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
fun ClawCard(
    modifier: Modifier = Modifier,
    containerColor: Color = ClawCardBackground,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, ClawBorder), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        content()
    }
}

@Composable
fun ClawActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    @DrawableRes iconRes: Int? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (selected) ClawInk else ClawCardBackground,
                contentColor = if (selected) Color.White else ClawInk,
                disabledContainerColor = Color(0xFFF0F1F3),
                disabledContentColor = Color(0xFF9CA3AE),
            ),
        border = if (selected) null else BorderStroke(1.dp, ClawBorder),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ClawChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (selected) ClawGreenMuted else ClawCardBackground,
                contentColor = if (selected) Color(0xFF1F9B05) else ClawInk,
                disabledContainerColor = Color(0xFFF0F1F3),
                disabledContentColor = Color(0xFF9CA3AE),
            ),
        border = BorderStroke(1.dp, if (selected) ClawGreen else ClawBorder),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ClawPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = ClawInk,
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFE0E2E6),
                disabledContentColor = Color(0xFF9CA3AE),
            ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ClawIconBadge(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    background: Color = ClawGreenMuted,
    iconColor: Color = ClawGreen,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
fun ClawInfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    badge: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ClawCard(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (iconRes != null) {
                Box(
                    modifier =
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ClawGreenMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = ClawInk,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        color = ClawInk,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    badge?.let {
                        Box(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ClawGreenMuted)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = it,
                                color = ClawGreen,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    text = body,
                    color = ClawInkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(
                            text = actionLabel,
                            color = ClawGreen,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClawFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = ClawInkMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(0.85f),
        )
        Text(
            text = value,
            color = ClawInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun ClawStatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) ClawGreen else Color(0xFFC9CED6)),
    )
}
