package com.blaze.assistant.mediaapps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.BlazeMenu
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.LocalBlaze

/**
 * Wave B item 20: the top bar while picking library items: close, "N selected", then Select all, Add to playlist (Music),
 * Share, Export and Delete as 38 dp round icons. Sized for a 360 dp phone: the label takes what is left and ellipsizes;
 * the six controls need 228 dp, leaving ~100 dp for "120 selected" after the 14 dp side padding.
 */
@Composable
fun SelectionBar(
    label: String, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit,
    onShare: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit, onAddToPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    exportItems: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var exportMenu by remember { mutableStateOf(false) }
    val c = LocalBlaze.current
    Row(modifier.fillMaxWidth().height(48.dp).clip(BlazeShapes.pill).background(c.faint).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.Rounded.Close, "Cancel selection", size = 38, onClick = onClose)
        Spacer(Modifier.width(4.dp))
        MText(label, 15.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.weight(1f))
        RoundIcon(Icons.Rounded.SelectAll, if (allSelected) "Select none" else "Select all", size = 38,
            tint = if (allSelected) c.accent else c.ink, onClick = onSelectAll)
        if (onAddToPlaylist != null) RoundIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", size = 38, onClick = onAddToPlaylist)
        RoundIcon(Icons.Rounded.Share, "Share", size = 38, onClick = onShare)
        // Wave B 2: with targets given, Export opens a menu (Music/Movies, Downloads, a folder); else straight to the folder picker.
        Box {
            RoundIcon(Icons.Rounded.SaveAlt, "Export", size = 38, onClick = { if (exportItems.isEmpty()) onExport() else exportMenu = true })
            BlazeMenu(exportMenu, { exportMenu = false }, exportItems)
        }
        RoundIcon(Icons.Rounded.Delete, "Delete", size = 38, onClick = onDelete)
    }
}

/** The pick mark on a row or tile in selection mode: a filled check when picked, an empty ring when not. */
@Composable
fun SelectMark(selected: Boolean, modifier: Modifier = Modifier, onArt: Boolean = false) {
    val c = LocalBlaze.current
    Box(modifier.size(26.dp).clip(BlazeShapes.pill).background(if (onArt) Color.Black.copy(alpha = 0.35f) else Color.Transparent),
        contentAlignment = Alignment.Center) {
        Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (selected) "Selected" else "Not selected",
            tint = if (selected) c.accent else if (onArt) Color.White else c.sub, modifier = Modifier.size(22.dp))
    }
}
