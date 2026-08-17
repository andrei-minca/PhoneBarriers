package ro.andi.phonebarriers.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import ro.andi.phonebarriers.data.Barrier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarrierForm(
    barrier: Barrier? = null,
    currentLocation: LatLng? = null,
    onSave: (Barrier) -> Unit,
    onCancel: () -> Unit
) {
    var shortName by remember { mutableStateOf(barrier?.shortName ?: "") }
    var description by remember { mutableStateOf(barrier?.description ?: "") }
    var color by remember { mutableStateOf(barrier?.color ?: ColorValues().randomOrNull()?.toArgb() ?: Color.Red.toArgb()) }
    var phoneTo by remember { mutableStateOf(barrier?.phoneNumberTo ?: "") }
    var phoneFrom by remember { mutableStateOf(barrier?.phoneNumberFrom ?: "") }
    var latitude by remember { mutableStateOf(barrier?.latitude ?: (currentLocation?.latitude ?: 0.0) ) }

    var shortNameError by remember { mutableStateOf(false) }
    var descriptionError by remember { mutableStateOf(false) }
    var phoneToError by remember { mutableStateOf(false) }
    var phoneFromError by remember { mutableStateOf(false) }
    var longitude by remember { mutableStateOf(barrier?.longitude ?: (currentLocation?.longitude ?: 45.0) ) }
    var radius by remember { mutableStateOf(barrier?.radius ?: 50f) }
    var hasOptedAutoTrigger by remember { mutableStateOf(barrier?.hasOptedAutoTrigger ?: false) }
    var showOptInfo by remember { mutableStateOf(false) }
    var columnScrollingEnabled by remember { mutableStateOf(true) }

    if (showOptInfo) {
        AlertDialog(
            onDismissRequest = { showOptInfo = false },
            title = { Text("Lift-Learning & Auto-Trigger") },
            text = { Text("This feature will learn from your manual lifts and automatically trigger the barrier when you are within the specified radius. Location permissions will be required for background tracking.") },
            confirmButton = {
                TextButton(onClick = { showOptInfo = false }) {
                    Text("OK")
                }
            }
        )
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(latitude, longitude), 15f)
    }

    LaunchedEffect(cameraPositionState.position.target) {
        latitude = cameraPositionState.position.target.latitude
        longitude = cameraPositionState.position.target.longitude
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState(), enabled = columnScrollingEnabled),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Barrier Info" + " (id: ${barrier?.id?:"0"})", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = shortName,
            onValueChange = {
                shortName = it
                if (shortNameError) shortNameError = it.isBlank()
            },
            label = { Text("Short Name") },
            modifier = Modifier.fillMaxWidth(),
            isError = shortNameError,
            supportingText = { if (shortNameError) Text("Short Name is required") }
        )
        OutlinedTextField(
            value = description,
            onValueChange = {
                description = it
                if (descriptionError) descriptionError = it.isBlank()
            },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
            isError = descriptionError,
            supportingText = { if (descriptionError) Text("Description is required") }
        )
        ColorInput(label = "Color:", selectedColor = color, onColorSelected = { color = it })
        OutlinedTextField(
            value = phoneTo,
            onValueChange = {
                phoneTo = it
                if (phoneToError) phoneToError = it.isBlank()
            },
            label = { Text("Barrier Phone Number (To)") },
            modifier = Modifier.fillMaxWidth(),
            isError = phoneToError,
            supportingText = { if (phoneToError) Text("Barrier phone number is required") }
        )
        OutlinedTextField(
            value = phoneFrom,
            onValueChange = {
                phoneFrom = it
                if (phoneFromError) phoneFromError = it.isBlank()
            },
            label = { Text("Caller Phone Number (From)") },
            modifier = Modifier.fillMaxWidth(),
            isError = phoneFromError,
            supportingText = { if (phoneFromError) Text("Caller phone number is required") }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Opt for Lift-Learning & Auto-Trigger", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = hasOptedAutoTrigger,
                onCheckedChange = {
                    if (!hasOptedAutoTrigger && it) {
                        showOptInfo = true
                    }
                    hasOptedAutoTrigger = it
                }
            )
        }

        if (hasOptedAutoTrigger) {
            Text("Barrier Location & Auto-Trigger Radius", style = MaterialTheme.typography.titleMedium)

            Box(
                modifier = Modifier
                    .height(300.dp)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val dragEvent = event.changes.any { it.pressed }
                                if (dragEvent) {
                                    columnScrollingEnabled = false
                                } else {
                                    columnScrollingEnabled = true
                                }
                            }
                        }
                    }
            ) {
                val markerState = rememberMarkerState(position = LatLng(latitude, longitude))

                // Sync marker position if latitude/longitude changes externally (though here it's vice versa)
                LaunchedEffect(latitude, longitude) {
                    markerState.position = LatLng(latitude, longitude)
                }

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState
                ) {
                    Marker(
                        state = markerState,
                        title = "Barrier Location"
                    )
                    Circle(
                        center = LatLng(latitude, longitude),
                        radius = radius.toDouble(),
                        fillColor = Color.Red.copy(alpha = 0.3f),
                        strokeColor = Color.Red,
                        strokeWidth = 2f
                    )
                }
            }

            Slider(
                value = radius,
                onValueChange = { radius = it },
                valueRange = 10f..500f,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Auto-Trigger Radius: ${radius.toInt()} meters")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                shortNameError = shortName.isBlank()
                descriptionError = description.isBlank()
                phoneToError = phoneTo.isBlank()
                phoneFromError = phoneFrom.isBlank()

                if (!shortNameError && !descriptionError && !phoneToError && !phoneFromError) {
                    onSave(
                        Barrier(
                            id = barrier?.id ?: 0,
                            shortName = shortName,
                            description = description,
                            color = color,
                            phoneNumberTo = phoneTo,
                            phoneNumberFrom = phoneFrom,
                            latitude = latitude,
                            longitude = longitude,
                            radius = radius,
                            hasOptedAutoTrigger = hasOptedAutoTrigger,
                            isEnabledAutoTrigger = barrier?.isEnabledAutoTrigger ?: true,
                            countLift = barrier?.countLift ?: 0,
                            countLiftNLearn = barrier?.countLiftNLearn ?: 0,
                            countAutoTriggered = barrier?.countAutoTriggered ?: 0
                        )
                    )
                }
            }) {
                Text("Save")
            }
        }
    }
}
