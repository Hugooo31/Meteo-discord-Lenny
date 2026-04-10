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
    private static final String GROK_API_KEY = System.getenv().getOrDefault("GROK_API_KEY", System.getenv().getOrDefault("XAI_API_KEY", ""));
    private static final String GROK_MODEL = System.getenv().getOrDefault("GROK_MODEL", "grok-2-latest");

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
        String funnyLine = generateFunnyLine(client, condition, tempMin, tempMax, precip, windspeed);

        String message = String.format(
            "## %s Météo du jour — %s — %s\\n" +
            "**%s**\\n\\n" +
            "😄 **Phrase du jour :** %s\\n\\n" +
            "🌡️ **Températures :** %.1f°C / %.1f°C\\n" +
            "🌧️ **Précipitations :** %.1f mm\\n" +
            "💨 **Vent max :** %.1f km/h",
            emoji, CITY_NAME, date,
            condition,
            funnyLine,
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
        int endComma  = json.indexOf(",", start);
        int endBracket = json.indexOf("]", start);
        // Prendre le plus proche des deux délimiteurs
        int end = (endComma != -1 && endComma < endBracket) ? endComma : endBracket;
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

    private static String generateFunnyLine(HttpClient client, String condition, double tempMin, double tempMax, double precip, double windspeed) {
        if (GROK_API_KEY == null || GROK_API_KEY.isBlank()) {
            return fallbackFunnyLine(condition, tempMax, precip, windspeed);
        }

        try {
            String prompt = String.format(
                "Génère UNE phrase courte et drôle en français sur cette météo. " +
                "Maximum 12 mots. Pas de guillemets, pas de hashtags, pas d'explication. " +
                "Météo: %s, min %.1f°C, max %.1f°C, pluie %.1f mm, vent %.1f km/h.",
                condition, tempMin, tempMax, precip, windspeed
            );

            String body = "{" +
                "\"model\":\"" + escapeJson(GROK_MODEL) + "\"," +
                "\"messages\":[" +
                    "{\"role\":\"system\",\"content\":\"Tu es un assistant météo drôle et concis.\"}," +
                    "{\"role\":\"user\",\"content\":\"" + escapeJson(prompt) + "\"}" +
                "]," +
                "\"temperature\":0.8," +
                "\"max_tokens\":40" +
            "}";

            HttpRequest grokRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.x.ai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GROK_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> grokResponse = client.send(grokRequest, HttpResponse.BodyHandlers.ofString());
            if (grokResponse.statusCode() >= 200 && grokResponse.statusCode() < 300) {
                String line = extractContentFromChatResponse(grokResponse.body());
                if (line != null && !line.isBlank()) {
                    return line.trim().replace("\n", " ");
                }
            } else {
                System.err.println("⚠️ Grok indisponible (" + grokResponse.statusCode() + "), fallback local.");
            }
        } catch (Exception e) {
            System.err.println("⚠️ Erreur Grok, fallback local: " + e.getMessage());
        }

        return fallbackFunnyLine(condition, tempMax, precip, windspeed);
    }

    private static String fallbackFunnyLine(String condition, double tempMax, double precip, double windspeed) {
        if (precip > 5) return "Parapluie obligatoire, coiffure optionnelle.";
        if (condition.toLowerCase().contains("orage")) return "Le ciel lance des rage-quits aujourd'hui.";
        if (tempMax >= 28) return "Journée sans t-shirt, mode grille-pain activé.";
        if (tempMax <= 5) return "Il fait froid au point que mon café demande une doudoune.";
        if (windspeed >= 45) return "Coiffure aérodynamique offerte par le vent.";
        if (condition.toLowerCase().contains("neige")) return "Bonhomme de neige en réunion de chantier.";
        return "Météo correcte, excuses pour rester au lit invalidées.";
    }

    private static String extractContentFromChatResponse(String json) {
        String key = "\"content\":\"";
        int idx = json.indexOf(key);
        if (idx == -1) return null;

        int start = idx + key.length();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;

        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }

        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ");
    }
}