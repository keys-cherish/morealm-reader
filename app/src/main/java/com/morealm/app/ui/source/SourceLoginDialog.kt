package com.morealm.app.ui.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.presentation.source.LoginField

@Composable
fun SourceLoginDialog(
    source: BookSource,
    fields: List<LoginField>,
    onDismiss: () -> Unit,
    onLogin: (Map<String, String>) -> Unit,
) {
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    val focusManager = LocalFocusManager.current

    // Pre-fill with saved login info
    LaunchedEffect(source) {
        source.getLoginInfoMap()?.let { saved ->
            fieldValues.putAll(saved)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录 ${source.bookSourceName}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fields.forEachIndexed { index, field ->
                    val isLast = index == fields.lastIndex
                    OutlinedTextField(
                        value = fieldValues[field.name] ?: "",
                        onValueChange = { fieldValues[field.name] = it },
                        label = { Text(field.hint.ifBlank { field.name }) },
                        placeholder = { Text(field.hint) },
                        visualTransformation = if (field.type == "password") {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (field.type) {
                                "number" -> KeyboardType.Number
                                "password" -> KeyboardType.Password
                                else -> KeyboardType.Text
                            },
                            imeAction = if (isLast) ImeAction.Done else ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onDone = {
                                focusManager.clearFocus()
                                onLogin(fieldValues.toMap())
                            },
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLogin(fieldValues.toMap()) },
                enabled = fields.all { fieldValues[it.name]?.isNotBlank() == true },
            ) {
                Text("登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
