package dev.quantumink.hermesgadget.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WelcomeScreen()
            }
        }
    }
}

@Composable
private fun WelcomeScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF11170F))
            .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = stringResource(R.string.gadget_title),
            color = Color(0xFFB6ED93),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(R.string.setup_title),
            color = Color(0xFFF0F3EB),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = stringResource(R.string.setup_intro),
            color = Color(0xFFBEC7B7),
            style = MaterialTheme.typography.bodyLarge
        )
        InfoBlock(R.string.server_title, R.string.server_body)
        InfoBlock(R.string.relay_title, R.string.relay_body)
        Text(
            text = stringResource(R.string.privacy_note),
            color = Color(0xFFB6ED93),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoBlock(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            color = Color(0xFFF0F3EB),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(body),
            color = Color(0xFFBEC7B7),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
