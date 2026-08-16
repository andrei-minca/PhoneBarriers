package ro.andi.phonebarriers.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import ro.andi.phonebarriers.data.Barrier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarrierForm(
    barrier: Barrier? = null,
    onSave: (Barrier) -> Unit,
    onCancel: () -> Unit
) {
    var shortName by remember { mutableStateOf(barrier?.shortName ?: "") }
    var description by remember { mutableStateOf(barrier?.description ?: "") }
    var color by remember { mutableStateOf(barrier?.color ?: Color.Red.toArgb()) }
    var phoneTo by remember { mutableStateOf(barrier?.phoneNumberTo ?: "") }
    var phoneFrom by remember { mutableStateOf(barrier?.phoneNumberFrom ?: "") }
    var latitude by remember { mutableStateOf(barrier?.latitude ?: 0.0) }
    var longitude by remember { mutableStateOf(barrier?.longitude ?: 0.0) }
    var radius by remember { mutableStateOf(barrier?.radius ?: 50f) }

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
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Barrier Info", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(value = shortName, onValueChange = { shortName = it }, label = { Text("Short Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
        ColorInput(label = "Color:", selectedColor = color, onColorSelected = { color = it })
        OutlinedTextField(value = phoneTo, onValueChange = { phoneTo = it }, label = { Text("Barrier Phone Number (To)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = phoneFrom, onValueChange = { phoneFrom = it }, label = { Text("Caller Phone Number (From)") }, modifier = Modifier.fillMaxWidth())
        
        Text("Location & Auto-Trigger-Radius", style = MaterialTheme.typography.titleMedium)
        
        Box(modifier = Modifier.height(300.dp).fillMaxWidth()) {
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
        Text("Auto-Trigger-Radius: ${radius.toInt()} meters")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
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
                        radius = radius
                    )
                )
            }) {
                Text("Save")
            }
        }
    }
}
