package ro.andi.phonebarriers.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import ro.andi.phonebarriers.data.Barrier

@Composable
fun BarrierListItem(
    barrier: Barrier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLift: () -> Unit,
    onLiftNLearn: () -> Unit,
    onToggleAutoTrigger: (Boolean) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Barrier") },
            text = { Text("Are you sure you want to delete '${barrier.shortName}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, Color.Black, RoundedCornerShape(12.dp))
            .padding(1.dp)
            .border(6.dp, Color(barrier.color), RoundedCornerShape(11.dp))
            .padding(6.dp)
            .border(1.dp, Color.Black, RoundedCornerShape(7.dp)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            )
            {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = barrier.shortName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = barrier.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1.2f)) {
                    Text("Barrier : ${barrier.phoneNumberTo}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Caller  : ${barrier.phoneNumberFrom}", style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = onLift,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Lift", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (barrier.hasOptedAutoTrigger) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .height(150.dp)
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .weight(1.2f)
                    )
                    {
                        val cameraPositionState = rememberCameraPositionState {
                            position = CameraPosition.fromLatLngZoom(LatLng(barrier.latitude, barrier.longitude), 15f)
                        }
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            uiSettings = MapUiSettings(zoomControlsEnabled = false, scrollGesturesEnabled = false),
                            googleMapOptionsFactory = { com.google.android.gms.maps.GoogleMapOptions().liteMode(true) }
                        ) {
                            Marker(state = rememberMarkerState(position = LatLng(barrier.latitude, barrier.longitude)))
                            Circle(
                                center = LatLng(barrier.latitude, barrier.longitude),
                                radius = barrier.radius.toDouble(),
                                fillColor = Color.Red.copy(alpha = 0.2f),
                                strokeColor = Color.Red,
                                strokeWidth = 2f
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Switch(
                                checked = barrier.isEnabledAutoTrigger,
                                onCheckedChange = onToggleAutoTrigger,
                                modifier = Modifier.scale(0.9f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Auto Trigger", style = MaterialTheme.typography.labelLarge)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = onLiftNLearn,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088FF))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Lift & Learn", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
