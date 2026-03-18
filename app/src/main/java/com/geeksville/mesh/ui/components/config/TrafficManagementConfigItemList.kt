package com.geeksville.mesh.ui.components.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.model.RadioConfigViewModel
import com.geeksville.mesh.ui.components.EditTextPreference
import com.geeksville.mesh.ui.components.PreferenceCategory
import com.geeksville.mesh.ui.components.PreferenceFooter
import com.geeksville.mesh.ui.components.SwitchPreference
import org.meshtastic.proto.ModuleConfigProtos
import org.meshtastic.proto.copy
import org.meshtastic.proto.moduleConfig


@Composable
@Suppress("AssignedValueIsNeverRead")
fun TrafficManagementConfigScreen(viewModel: RadioConfigViewModel = hiltViewModel()
) {
    val state by viewModel.radioConfigState.collectAsStateWithLifecycle()
    if (state.responseState.isWaiting()) {
        PacketResponseStateDialog(
            state = state.responseState,
            onDismiss = viewModel::clearPacketResponse,
        )
    }

    TrafficManagementConfigItemList(
        trafficManagementConfig = state.moduleConfig.trafficManagement,
        enabled = state.connected,
        onSaveClicked = { trafficManagementInput ->
            val config = moduleConfig { trafficManagement = trafficManagementInput }
            viewModel.setModuleConfig(config)
        }
    )
}

@Composable
fun TrafficManagementConfigItemList(
    trafficManagementConfig: ModuleConfigProtos.ModuleConfig.TrafficManagementConfig,
    enabled: Boolean,
    onSaveClicked: (ModuleConfigProtos.ModuleConfig.TrafficManagementConfig) -> Unit
) {

    val focusManager = LocalFocusManager.current
    var trafficManagementInput by rememberSaveable { mutableStateOf(trafficManagementConfig) }

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {

        item { PreferenceCategory(text = "Traffic Management Config") }

        item {
            SwitchPreference(title = "Traffic Management Enabled",
                checked = trafficManagementInput.enabled,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy { this.enabled = it }
                })
        }
        item { Divider() }

        item {
            SwitchPreference(title = "Position Dedup",
                checked = trafficManagementInput.positionDedupEnabled,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy { this.positionDedupEnabled = it }
                })
        }

        item { Divider() }

        item {
            EditTextPreference(title = "Position Precision",
                value = trafficManagementInput.positionPrecisionBits,
                enabled = enabled,
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                onValueChanged = {
                    trafficManagementInput = trafficManagementInput.copy { positionPrecisionBits = it }
                })
        }

        item { Divider() }

        item {
            EditTextPreference(title = "Position Min Interval",
                value = trafficManagementInput.positionMinIntervalSecs,
                enabled = enabled,
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                onValueChanged = {
                    trafficManagementInput = trafficManagementInput.copy { positionMinIntervalSecs = it }
                })
        }

        item { Divider() }

        item {
            SwitchPreference(
                title = "NodeInfo Direct Response",
                checked = trafficManagementInput.nodeinfoDirectResponse,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy {
                        nodeinfoDirectResponse = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            EditTextPreference(
                title = "NodeInfo Direct Response Max Hops",
                value = trafficManagementInput.nodeinfoDirectResponseMaxHops,
                enabled = enabled,
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                onValueChanged = {
                    trafficManagementInput = trafficManagementInput.copy {
                        nodeinfoDirectResponseMaxHops = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            SwitchPreference(
                title = "Rate Limit Enabled",
                checked = trafficManagementInput.rateLimitEnabled,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy {
                        rateLimitEnabled = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            EditTextPreference(
                title = "Rate Limit Window",
                value = trafficManagementInput.rateLimitWindowSecs,
                enabled = enabled,
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                onValueChanged = {
                    trafficManagementInput = trafficManagementInput.copy {
                        rateLimitWindowSecs = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            EditTextPreference(
                title = "Rate Limit Max Packets",
                value = trafficManagementInput.rateLimitMaxPackets,
                enabled = enabled,
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                onValueChanged = {
                    trafficManagementInput = trafficManagementInput.copy {
                        rateLimitMaxPackets = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            SwitchPreference(
                title = "Drop Unknown Enabled",
                checked = trafficManagementInput.dropUnknownEnabled,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy {
                        dropUnknownEnabled = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            EditTextPreference(
                title = "Unknown Packet Threshold",
                value = trafficManagementInput.unknownPacketThreshold,
                enabled = enabled,
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                onValueChanged = {
                    trafficManagementInput = trafficManagementInput.copy {
                        unknownPacketThreshold = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            SwitchPreference(
                title = "Exhaust Hop Telemetry",
                checked = trafficManagementInput.exhaustHopTelemetry,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy {
                        exhaustHopTelemetry = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            SwitchPreference(
                title = "Exhaust Hop Position",
                checked = trafficManagementInput.exhaustHopPosition,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy {
                        exhaustHopPosition = it
                    }
                }
            )
        }

        item { Divider() }

        item {
            SwitchPreference(
                title = "Router Preserve Hops",
                checked = trafficManagementInput.routerPreserveHops,
                enabled = enabled,
                onCheckedChange = {
                    trafficManagementInput = trafficManagementInput.copy {
                        routerPreserveHops = it
                    }
                }
            )
        }

        item {
            PreferenceFooter(
                enabled = enabled && trafficManagementInput != trafficManagementConfig,
                onCancelClicked = {
                    focusManager.clearFocus()
                    trafficManagementInput = trafficManagementConfig
                },
                onSaveClicked = {
                    focusManager.clearFocus()
                    onSaveClicked(trafficManagementInput)
                }
            )
        }
    }
}




