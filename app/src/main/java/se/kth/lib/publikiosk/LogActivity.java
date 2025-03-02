package se.kth.lib.publikiosk;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class LogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.webview_log);

        // Hämta TextView från layouten
        TextView logTextView = findViewById(R.id.logTextView);

        // Läs loggen från fil
        String logContent = readLogFromFile();
        if (logContent != null) {
            // Sätt loggens innehåll till TextView
            logTextView.setText(logContent);
        } else {
            logTextView.setText("No log available.");
        }
    }

    // Metod för att läsa loggen från filen
    private String readLogFromFile() {
        File directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (directory == null) {
            return null;
        }

        File logFile = new File(directory, "webview_logs.txt");

        // Kontrollera om filen existerar
        if (!logFile.exists()) {
            return null;
        }

        StringBuilder stringBuilder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return stringBuilder.toString();
    }
}
