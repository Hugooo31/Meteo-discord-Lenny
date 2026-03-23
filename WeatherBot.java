import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

public class WeatherBot {

    // === CONFIGURATION ===
    private static final String WEBHOOK_URL = System.getenv("DISCORD_WEBHOOK_URL");
    private static final double LATITUDE  = Double.parseDouble(System.getenv().getOrDefault("LATITUDE",  "43.6047")); // Toulouse par défaut
    private static final double LONGITUDE = Double.parseDouble(System.getenv().getOrDefault("LONGITUDE", "1.4442"));
    private static final String CITY_NAME = System.getenv().getOrDefault("CITY_NAME", "Toulouse");

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // 1. Fetch météo depuis Open-Meteo
        String meteoUrl = String.format(
            "https://api.open-meteo.com/v1/forecast" +
            "?latitude=%.4f&longitude=%.4f" +
            "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_sum,windspeed_10m_max" +
            "&timezone=Europe%%2FParis&forecast_days=1",
            LATITUDE, LONGITUDE
        );

        HttpRequest meteoRequest = HttpRequest.newBuilder()
            .uri(URI.create(meteoUrl))
            .GET()
            .build();

        HttpResponse<String> meteoResponse = client.send(meteoRequest, HttpResponse.BodyHandlers.ofString());
        String meteoJson = meteoResponse.body();

        // 2. Parser le JSON manuellement (sans dépendance externe)
        double tempMax   = extractFirst(meteoJson, "temperature_2m_max");
        double tempMin   = extractFirst(meteoJson, "temperature_2m_min");
        double precip    = extractFirst(meteoJson, "precipitation_sum");
        double windspeed = extractFirst(meteoJson, "windspeed_10m_max");
        int    wmoCode   = (int) extractFirst(meteoJson, "weathercode");

        // 3. Construire le message Discord
        String date      = LocalDate.now().toString();
        String emoji     = wmoToEmoji(wmoCode);
        String condition = wmoToLabel(wmoCode);

        String message = String.format(
            "## %s Météo du jour — %s — %s\\n" +
            "**%s**\\n\\n" +
            "🌡️ **Températures :** %.1f°C / %.1f°C\\n" +
            "🌧️ **Précipitations :** %.1f mm\\n" +
            "💨 **Vent max :** %.1f km/h",
            emoji, CITY_NAME, date,
            condition,
            tempMin, tempMax,
            precip,
            windspeed
        );

        // 4. Envoyer au webhook Discord
        String payload = "{\"content\": \"" + message.replace("\"", "\\\"") + "\"}";

        HttpRequest webhookRequest = HttpRequest.newBuilder()
            .uri(URI.create(WEBHOOK_URL))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> webhookResponse = client.send(webhookRequest, HttpResponse.BodyHandlers.ofString());

        if (webhookResponse.statusCode() == 204) {
            System.out.println("✅ Message envoyé avec succès !");
        } else {
            System.err.println("❌ Erreur webhook : " + webhookResponse.statusCode());
            System.err.println(webhookResponse.body());
        }
    }

    // Extrait la première valeur numérique d'un tableau JSON pour une clé donnée
    private static double extractFirst(String json, String key) {
        String search = "\"" + key + "\":[";
        int idx = json.indexOf(search);
        if (idx == -1) return 0;
        int start = idx + search.length();
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("]", start);
        return Double.parseDouble(json.substring(start, end).trim());
    }

    // Codes WMO → emoji
    private static String wmoToEmoji(int code) {
        if (code == 0)                    return "☀️";
        if (code <= 2)                    return "🌤️";
        if (code == 3)                    return "☁️";
        if (code >= 45 && code <= 48)     return "🌫️";
        if (code >= 51 && code <= 67)     return "🌦️";
        if (code >= 71 && code <= 77)     return "❄️";
        if (code >= 80 && code <= 82)     return "🌧️";
        if (code >= 95 && code <= 99)     return "⛈️";
        return "🌡️";
    }

    // Codes WMO → label français
    private static String wmoToLabel(int code) {
        if (code == 0)                    return "Ciel dégagé";
        if (code == 1)                    return "Principalement dégagé";
        if (code == 2)                    return "Partiellement nuageux";
        if (code == 3)                    return "Couvert";
        if (code >= 45 && code <= 48)     return "Brouillard";
        if (code >= 51 && code <= 55)     return "Bruine";
        if (code >= 61 && code <= 65)     return "Pluie";
        if (code >= 66 && code <= 67)     return "Pluie verglaçante";
        if (code >= 71 && code <= 77)     return "Neige";
        if (code >= 80 && code <= 82)     return "Averses";
        if (code >= 85 && code <= 86)     return "Averses de neige";
        if (code >= 95 && code <= 99)     return "Orage";
        return "Conditions inconnues";
    }
}
