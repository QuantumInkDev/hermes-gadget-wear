package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.client.CuePreferences

@Composable
fun PhoneSettingsPage(context: Context, navigate: (String) -> Unit) {
    val soundCues by CuePreferences.enabled.collectAsStateWithLifecycle()
    Text("Settings", style = MaterialTheme.typography.headlineMedium)
    Button({ navigate("appearance") }) { Text("Appearance") }
    Button({ navigate("boxes") }) { Text("Boxes and discovery") }
    Button({ navigate("manual") }) { Text("Manual watch endpoints") }
    Button({ navigate("speech") }) { Text("Watch speech providers") }
    Button({ CuePreferences.set(context, !soundCues) }) {
        Text("Recording sound cues: " + if (soundCues) "on" else "off")
    }
    Text("About Quantum Relay for Hermes", style = MaterialTheme.typography.titleMedium)
    Text(
        "Quantum Relay for Hermes is an independent QuantumInk companion for Gadget SDK v0.2.0. " +
            "Not affiliated with or endorsed by Nous Research. " +
            "Hermes and Nous Research names and marks belong to their owners. " +
            "The orbit/speech icon is original QuantumInk artwork."
    )
}
