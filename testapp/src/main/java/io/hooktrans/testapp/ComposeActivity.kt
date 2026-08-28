package io.hooktrans.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Compose target. Compose never creates a TextView, so this exercises an entirely separate
 * hook — the paragraph intrinsics constructor — and is the case a TextView-only translator
 * misses completely.
 */
class ComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var extra by remember { mutableStateOf(0) }
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    Text("Compose screen", style = MaterialTheme.typography.headlineSmall)
                    Text("Your subscription renews next month")
                    Text("Tap the button below to add more items")
                    Text("Payment method")
                    Text("https://example.com/billing")   // must survive untouched
                    Text("%1\$d items remaining")          // must survive untouched

                    Button(onClick = { extra++ }) { Text("Add an item") }

                    // Content that appears only after interaction: the dynamic case again,
                    // but on the Compose pipeline.
                    repeat(extra) { i ->
                        Text("Item number ${i + 1} was added just now")
                    }
                }
            }
        }
    }
}
