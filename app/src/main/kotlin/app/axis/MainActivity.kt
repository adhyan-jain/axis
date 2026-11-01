package app.axis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.axis.ui.AxisApp
import app.axis.ui.AxisTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AxisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ignore touches while another app draws over Axis (tapjacking defense for the confirm button).
        window.decorView.filterTouchesWhenObscured = true
        setContent { AxisTheme { AxisApp(viewModel) } }
    }
}
