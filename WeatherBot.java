import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

public class WeatherBot {

    // === CONFIGURATION ===
    private static final String WEBHOOK_URL = System.getenv("DISCORD_WEBHOOK_URL");
    private static final double LATITUDE  = Double.parseDouble(System.getenv().getOrDefault("LATITUDE",  "43.6047")); // Toulouse par défaut
    private static final double LONGITUDE = Double.parseDouble(System.getenv().getOrDefault("LONGITUDE", "1.4442"));
    private static final String CITY_NAME = System.getenv().getOrDefault("CITY_NAME", "Toulouse");
    private static final String GROK_API_KEY = System.getenv().getOrDefault("GROK_API_KEY", System.getenv().getOrDefault("XAI_API_KEY", ""));
    private static final String GROK_MODEL = System.getenv().getOrDefault("GROK_MODEL", "grok-2-latest");
    private static final String[] TARGET_NAMES = {"Yann", "Baptiste", "Lenny", "Colin", "Hugo"};

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
        String targetName = pickRandomName();
        String titleLine = buildTitle(condition, tempMax, precip, windspeed);
        String trashLine = generateTrashLine(client, targetName, condition, tempMin, tempMax, precip, windspeed);

        String message = String.format(
            "## %s %s — %s — %s\\n" +
            "**%s**\\n\\n" +
            "🤬 %s\\n\\n" +
            "🌡️ **Températures :** %.1f°C / %.1f°C\\n" +
            "🌧️ **Précipitations :** %.1f mm\\n" +
            "💨 **Vent max :** %.1f km/h",
            emoji, titleLine, CITY_NAME, date,
            condition,
            trashLine,
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

        private static String generateTrashLine(HttpClient client, String targetName, String condition, double tempMin, double tempMax, double precip, double windspeed) {
        if (GROK_API_KEY == null || GROK_API_KEY.isBlank()) {
            return fallbackTrashLine(targetName, condition, tempMax, precip, windspeed);
        }

        try {
            String prompt = String.format(
                "Génère UNE phrase en français, ou tu insulte le nom que je te donne, sois mechant  très vulgaire et trash. " +
                "Insulte bien la personne de façon bien mechante. " +
                "Exemples de style pour l'en-tête : temps de merde / météo de fils de pute / temps de sa race / ciel de enculé / etc. " +
                "Pour la suite : sois encore plus insultant avec %s (genre 'espèce de bite molle', 'sale con', 'fils de pute', 'enculé', 'bouffon', 'gros tas', 'sale tafiole', 'petite merde', etc.). " +
                "Donne une consigne concrète selon la météo (mets un pull de merde, prends un parapluie ou crève dehors, reste dans ton trou, etc.). " +
                "Tutoiement obligatoire, langage très cru, pas de filtre. insulte la personne " +
                "Pas de guillemets, pas d'explication, pas de smiley, juste la phrase. " +
                "Météo : %s, min %.1f°C, max %.1f°C, pluie %.1f mm, vent %.1f km/h.",
                targetName, targetName, condition, tempMin, tempMax, precip, windspeed
            );

            String body = "{" +
                "\"model\":\"" + escapeJson(GROK_MODEL) + "\"," +
                "\"messages\":[" +
                    "{\"role\":\"system\",\"content\":\"Tu es un assistant météo ultra trash, vulgaire, méchant et sans aucun filtre. Tu insultes la personne et la météo de façon créative et bien dégueulasse. Format libre, aucune limite.\"}," +
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
                    String cleaned = line.trim().replace("\n", " ").replace("\"", "");
                    String expectedPrefix = targetName + ",";

                    if (cleaned.toLowerCase().startsWith(targetName.toLowerCase())) {
                        String rest = cleaned.substring(targetName.length()).trim();
                        if (rest.startsWith(",")) {
                            rest = rest.substring(1).trim();
                        }
                        cleaned = expectedPrefix + " " + rest;
                    } else {
                        cleaned = expectedPrefix + " " + cleaned;
                    }
                    return cleaned;
                }
            } else {
                System.err.println("⚠️ Grok indisponible (" + grokResponse.statusCode() + "), fallback local.");
            }
        } catch (Exception e) {
            System.err.println("⚠️ Erreur Grok, fallback local: " + e.getMessage());
        }

        return fallbackTrashLine(targetName, condition, tempMax, precip, windspeed);
    }

    
    private static String buildTitle(String condition, double tempMax, double precip, double windspeed) {
        if (condition.toLowerCase().contains("orage")) return "Coup de tonnerre sur la journée";
        if (precip > 5) return "Déluge en approche";
        if (windspeed >= 45) return "Journée soufflée";
        if (tempMax >= 28) return "Fournée humaine";
        if (tempMax <= 5) return "Congélo à ciel ouvert";
        if (condition.toLowerCase().contains("neige")) return "Poudreuse et chaos";
        return "Météo du sale temps";
    }

    private static String fallbackTrashLine(String targetName, String condition, double tempMax, double precip, double windspeed) {
        if (precip > 5) return targetName + ", il pleut sa race, prends un putain de parapluie.";
        if (condition.toLowerCase().contains("orage")) return targetName + ", ça pète dehors, reste dedans et ferme ta gueule.";
        if (tempMax >= 28) return targetName + ", ça cogne fort, bois de l'eau et reste à l'ombre bordel.";
        if (tempMax <= 5) return targetName + ", ça caille sévère, mets un putain de pull et couvre-toi.";
        if (windspeed >= 45) return targetName + ", ça souffle violent, prends une veste et sors pas n'importe comment.";
        if (condition.toLowerCase().contains("neige")) return targetName + ", ça neige sale, mets des pompes qui accrochent et fais gaffe.";
        return targetName + ", météo bâtarde, prends une veste et évite de traîner dehors.";
    }

    private static String pickRandomName() {
        int idx = ThreadLocalRandom.current().nextInt(TARGET_NAMES.length);
        return TARGET_NAMES[idx];
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