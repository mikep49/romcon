package dev.romcon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.romcon.AppViewModel
import dev.romcon.data.AppSettings
import dev.romcon.data.BarItem
import dev.romcon.data.CustomShortcut
import dev.romcon.data.KeyCombo
import dev.romcon.input.ModKey
import dev.romcon.input.PcKeys

internal val AppViewModel.shortcutEditorTitle: String
    get() = if (shortcutDraft?.original == null) "New shortcut" else "Edit shortcut"

/** Settings' page for making or changing a custom shortcut: its name, the hotkeys it presses, and its icon. */
@Composable
internal fun ShortcutEditor(vm: AppViewModel, s: AppSettings) {
    val draft = vm.shortcutDraft ?: return
    val sc = draft.shortcut
    /** The hotkey whose key is being chosen, by its place in the list. */
    var choosingKeyFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importShortcutImage(uri)
    }
    val editKey: (Int, (KeyCombo) -> KeyCombo) -> Unit = { index, change ->
        vm.editDraft { d -> d.copy(keys = d.keys.mapIndexed { i, k -> if (i == index) change(k) else k }) }
    }

    BarPreview(sc)

    Section("Name")
    OutlinedTextField(
        value = sc.name,
        onValueChange = { v -> vm.editDraft { it.copy(name = v.take(CustomShortcut.MAX_NAME)) } },
        placeholder = { Text("For example, Flow Launcher") },
        supportingText = { Text("Shown under the button. Leave it empty to show the keys.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    Section(if (sc.keys.size > 1) "Hotkeys" else "Hotkey")
    sc.keys.forEachIndexed { i, combo ->
        HotkeyCard(
            number = if (sc.keys.size > 1) i + 1 else null,
            combo = combo,
            onToggleMod = { bit -> editKey(i) { it.copy(mods = it.mods xor bit) } },
            onChooseKey = { choosingKeyFor = i },
            onRemove = if (sc.keys.size > 1) {
                { vm.editDraft { d -> d.copy(keys = d.keys.filterIndexed { j, _ -> j != i }) } }
            } else {
                null
            },
        )
    }
    if (sc.keys.size < CustomShortcut.MAX_KEYS) {
        TextButton(onClick = { vm.editDraft { it.copy(keys = it.keys + KeyCombo()) } }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add another hotkey")
        }
    }
    Help(
        if (sc.keys.size > 1) {
            "Pressed one after the other."
        } else {
            "Add another to press several in a row, like Ctrl+K then Ctrl+C."
        },
    )

    Section("Icon")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (sc.image == null) "Use a picture" else "Change picture")
        }
        if (sc.image != null) {
            TextButton(onClick = { vm.editDraft { it.copy(image = null) } }) { Text("Remove picture") }
        }
    }
    vm.shortcutImageError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Help("A logo with a transparent background looks best. Or pick one of these:")
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shortcutSymbols.forEach { symbol ->
            val selected = sc.image == null && sc.icon == symbol.key
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { vm.editDraft { it.copy(icon = symbol.key, image = null) } }
                    .semantics { contentDescription = symbol.label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    symbol.vector,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (draft.original == null && s.bottomBar.size >= BarItem.MAX) {
        Spacer(Modifier.padding(top = 16.dp))
        Help("The bar is full, so this one waits under Your shortcuts until you make room.")
    }
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (draft.original != null) {
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = vm::saveDraft, enabled = sc.ready && draft.changed) { Text("Save") }
    }

    choosingKeyFor?.let { i ->
        KeyPicker(
            current = sc.keys.getOrNull(i)?.vk ?: 0,
            onPick = { vk ->
                editKey(i) { it.copy(vk = vk) }
                choosingKeyFor = null
            },
            onDismiss = { choosingKeyFor = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${draft.original?.title ?: sc.title}?") },
            text = { Text("It's also taken off the bar.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteDraft()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (vm.confirmDiscard) {
        AlertDialog(
            onDismissRequest = vm::keepEditing,
            title = { Text(if (draft.original == null) "Discard this shortcut?" else "Discard your changes?") },
            confirmButton = { TextButton(onClick = vm::discardDraft) { Text("Discard") } },
            dismissButton = { TextButton(onClick = vm::keepEditing) { Text("Keep editing") } },
        )
    }
}

/** The button as it will look on the bar. */
@Composable
private fun BarPreview(sc: CustomShortcut) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ShortcutGlyph(sc, MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (sc.name.isBlank() && !sc.ready) "Shortcut" else sc.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** One hotkey: modifier chips and the key they go with. [number] is its place when there are several. */
@Composable
private fun HotkeyCard(number: Int?, combo: KeyCombo, onToggleMod: (Int) -> Unit, onChooseKey: () -> Unit, onRemove: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 14.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val text = if (combo.chosen || combo.mods != 0) combo.text else "No key chosen"
                Text(
                    if (number != null) "$number.  $text" else text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (combo.chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onRemove != null) {
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove hotkey $number") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModKey.entries.forEach { mod ->
                    FilterChip(selected = (combo.mods and mod.bit) != 0, onClick = { onToggleMod(mod.bit) }, label = { Text(mod.label) })
                }
            }
            val label = if (combo.chosen) "Key: ${PcKeys.name(combo.vk)}" else "Choose a key"
            val content: @Composable () -> Unit = {
                Text(label)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            Box(Modifier.padding(top = 4.dp)) {
                if (combo.chosen) {
                    OutlinedButton(onClick = onChooseKey) { content() }
                } else {
                    Button(onClick = onChooseKey) { content() }
                }
            }
        }
    }
}

/** Every key a hotkey can use, in groups. */
@Composable
private fun KeyPicker(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PcKeys.groups.forEachIndexed { i, group ->
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 16.dp, bottom = 6.dp),
                    )
                    group.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.keys.forEach { key ->
                            val on = key.vk == current
                            Surface(
                                onClick = { onPick(key.vk) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics {
                                    role = Role.Button
                                    selected = on
                                },
                            ) {
                                Box(
                                    Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 40.dp).padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(key.name, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
