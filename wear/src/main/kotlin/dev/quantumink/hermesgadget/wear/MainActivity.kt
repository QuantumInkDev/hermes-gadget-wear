package dev.quantumink.hermesgadget.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SetupScreen()
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(
            Color.Black
        ).padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = stringResource(R.string.gadget_title),
            color = Color(0xFFB6ED93),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.setup_title),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(R.string.setup_hint),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB3B8B0)
        )
        Text(
            text = stringResource(R.string.connection_status),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB3B8B0)
        )
    }
}
