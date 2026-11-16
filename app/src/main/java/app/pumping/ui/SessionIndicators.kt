package app.pumping.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pumping.R
import app.pumping.data.*

/** Informational only. A separate compact row prevents crowding timestamp/volume text. */
@Composable
internal fun SessionIndicators(session: Session, stashed: Boolean) {
    if (session.breastfedBefore != BreastfeedingStatus.YES && session.powerStatus != PowerPumpStatus.POWER && !stashed) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.breastfedBefore == BreastfeedingStatus.YES)
            Icon(painterResource(R.drawable.ic_breastfed), "Breastfed before pumping",
                Modifier.size(18.dp).testTag("breastfed-${session.id}"), tint = Pink)
        if (session.powerStatus == PowerPumpStatus.POWER)
            Icon(painterResource(R.drawable.ic_power_pump), "Power pump",
                Modifier.size(18.dp).testTag("power-${session.id}"), tint = Pink)
        if (stashed)
            Icon(Icons.Outlined.AcUnit, "Stashed", Modifier.size(18.dp).testTag("stashed-${session.id}"), tint = Blue)
    }
}
