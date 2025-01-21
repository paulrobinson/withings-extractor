///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.apache.httpcomponents.client5:httpclient5:5.2
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.2
//DEPS ch.qos.logback:logback-classic:1.4.11
//DEPS info.picocli:picocli:4.7.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.2
//DEPS com.fasterxml.jackson.core:jackson-core:2.15.2
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.15.2



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import picocli.CommandLine;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class run implements Callable {

    private static final String REDIRECT_URI = "http://localhost";
    private static final String REFRESH_TOKEN_FILE = ".refresh_token";

    @CommandLine.Option(names = {"-c", "--client-id"}, description = "Client ID", required = true)
    private String clientId;

    @CommandLine.Option(names = {"-s", "--client-secret"}, description = "Client Secret", required = true)
    private String clientSecret;

    public static void main(String... args) {
        int exitCode = new CommandLine(new run()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {

        // Check if the .refresh_token file exists
        String existingRefreshToken = loadRefreshTokenFromFile();

        if (existingRefreshToken != null) {
            System.out.println("Loaded existing refresh token: " + existingRefreshToken);
        } else {
            promptForRefreshCode();
            return 1;
        }


        String url = "https://wbsapi.withings.net/v2/oauth2";
        String parameters = "action=requesttoken" +
                "&grant_type=refresh_token" +
                "&client_id=" + clientId +
                "&client_secret=" + clientSecret +
                "&refresh_token=" + existingRefreshToken +
                "&redirect_uri=" + REDIRECT_URI;

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(parameters.getBytes());
        }

        int responseCode = connection.getResponseCode();
        System.out.println("Response Code: " + responseCode);

        try (InputStream is = connection.getInputStream();
            Scanner scanner = new Scanner(is)) {
            String response = scanner.useDelimiter("\\A").next();
            System.out.println("Response: " + response);

            // Create ObjectMapper
            ObjectMapper mapper = new ObjectMapper();

            // Parse JSON to a Map
            Map<String, Object> responseMap = mapper.readValue(response, Map.class);

            // Access "body" and its fields
            Map<String, Object> body = (Map<String, Object>) responseMap.get("body");
            String accessToken = (String) body.get("access_token");
            String updatedRefreshToken = (String) body.get("refresh_token");
            int userId = (int) body.get("userid");

            System.out.println("Access Token: " + accessToken);
            System.out.println("Refresh Token: " + updatedRefreshToken);
            System.out.println("User ID: " + userId);

            saveRefreshTokenToFile(updatedRefreshToken);

            fetchAndAggregateMeasurements(accessToken);
        }

        return 0;
    }

    private void promptForRefreshCode() {
        // Step 1: Print authorization URL
        String authUrl = String.format(
                "https://account.withings.com/oauth2_user/authorize2" +
                        "?response_type=code" +
                        "&client_id=%s" +
                        "&state=random_state_string" +
                        "&scope=user.metrics" +
                        "&redirect_uri=%s",
                clientId, REDIRECT_URI
        );

        System.out.println("Refresh Code parameter (\"-r\", \"--refresh-token\") missing. Visit this URL to get the authorization code if you don't have one. You will need to copy the 'code' parameter from the URL of the final page in the flow");
        System.out.println(authUrl);
    }

    private static String loadRefreshTokenFromFile() {
        File file = new File(REFRESH_TOKEN_FILE);
        if (file.exists()) {
            try {
                return new String(Files.readAllBytes(Paths.get(REFRESH_TOKEN_FILE))).trim();
            } catch (IOException e) {
                System.err.println("Error reading the refresh token file.");
                e.printStackTrace();
            }
        }
        return null;
    }

    private static void saveRefreshTokenToFile(String refreshToken) {

        try (FileWriter writer = new FileWriter(REFRESH_TOKEN_FILE)) {
            writer.write(refreshToken);
        } catch (IOException e) {
            System.err.println("Error writing to file: " + REFRESH_TOKEN_FILE);
            e.printStackTrace();
        }
    }

    private run() {

    }

    public void fetchAndAggregateMeasurements(String accessToken) {
        String url = "https://wbsapi.withings.net/measure?action=getmeas";
        long now = Instant.now().getEpochSecond();
        long threeWeeksAgo = now - (21 * 24 * 60 * 60); // 21 days in seconds

        Map<String, String> params = new HashMap<>();
        params.put("access_token", accessToken);
        params.put("meastype", "1,8,76"); // Weight, Fat %, Muscle
        params.put("category", "1"); // Real measurements
        params.put("startdate", String.valueOf(threeWeeksAgo));
        params.put("enddate", String.valueOf(now));

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url + "&" + buildQueryString(params));
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");

            try (CloseableHttpResponse response = client.execute(post)) {
                String rawResponse = readResponse(response);
                //System.out.println("Raw Response: " + rawResponse);

                if (response.getCode() != 200) {
                    System.err.println("Error: HTTP status " + response.getCode());
                    return;
                }

                // Parse JSON response
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(rawResponse);
                JsonNode bodyNode = rootNode.path("body");
                JsonNode measureGroups = bodyNode.path("measuregrps");

                // Process each measure group into a list of data points
                List<DataPoint> dataPoints = new ArrayList<>();
                for (JsonNode group : measureGroups) {
                    long timestamp = group.path("date").asLong();
                    Instant time = Instant.ofEpochSecond(timestamp);

                    double weight = 0.0, fatKg = 0.0, muscleKg = 0.0;

                    for (JsonNode measure : group.path("measures")) {
                        int type = measure.path("type").asInt();
                        double value = measure.path("value").asDouble();
                        int unit = measure.path("unit").asInt();
                        double actualValue = value * Math.pow(10, unit); // Correct scaling of value

                        switch (type) {
                            case 1:
                                weight = actualValue; // Weight in kg
                                //System.out.println("Weight: " + weight);
                                break;
                            case 8:
                                System.out.println(actualValue);
                                fatKg = actualValue; // allready in kg
                                break;
                            case 76:
                                muscleKg = actualValue; // Muscle in kg
                                break;
                            default:
                                //System.err.println("Unexpected measurement type: " + type);
                        }
                    }

                    dataPoints.add(new DataPoint(time, weight, fatKg, muscleKg));
                }

                // Aggregate by week
                Map<String, List<DataPoint>> groupedByWeek = dataPoints.stream()
                        .collect(Collectors.groupingBy(dp -> getWeekStart(dp.getTimestamp())));

                AtomicReference<Double> previousWeight = new AtomicReference<>(null);
                AtomicReference<Double> previousFatKg = new AtomicReference<>(null);
                AtomicReference<Double> previousMuscleKg = new AtomicReference<>(null);

                Map<String, List<DataPoint>> sortedGroupedByWeek = new TreeMap<>(groupedByWeek);

                sortedGroupedByWeek.forEach((week, points) -> {
                    // Calculate averages for the current week
                    double avgWeight = points.stream()
                            .filter(dp -> dp.getWeight() > 0)
                            .mapToDouble(DataPoint::getWeight)
                            .average()
                            .orElse(0);

                    double avgFatKg = points.stream()
                            .filter(dp -> dp.getFatKg() > 0)
                            .mapToDouble(DataPoint::getFatKg)
                            .average()
                            .orElse(0);

                    double avgMuscleKg = points.stream()
                            .filter(dp -> dp.getMuscleKg() > 0)
                            .mapToDouble(DataPoint::getMuscleKg)
                            .average()
                            .orElse(0);

                    // Calculate the delta from the previous week
                    double deltaWeight = previousWeight.get() != null ? avgWeight - previousWeight.get() : 0;
                    double deltaFatKg = previousFatKg.get() != null ? avgFatKg - previousFatKg.get() : 0;
                    double deltaMuscleKg = previousMuscleKg.get() != null ? avgMuscleKg - previousMuscleKg.get() : 0;



                    // Print the delta compared to the previous week
                    if (previousWeight.get() != null) {
                        // Print the week and the values
                        System.out.printf("Week of %s - Avg Weight: %.2f kg (%.2f), Avg Fat: %.2f kg (%.2f), Avg Muscle: %.2f kg (%.2f)%n",
                                week, avgWeight, deltaWeight, avgFatKg, deltaFatKg, avgMuscleKg,deltaMuscleKg);
                    } else {
                        // Print the week and the values
                        System.out.printf("Week of %s - Avg Weight: %.2f kg, Avg Fat: %.2f kg, Avg Muscle: %.2f kg%n",
                                week, avgWeight, avgFatKg, avgMuscleKg);
                    }

                    // Update the previous week's values for the next iteration
                    previousWeight.set(avgWeight);
                    previousFatKg.set(avgFatKg);
                    previousMuscleKg.set(avgMuscleKg);
                });



            }
        } catch (Exception e) {
            System.err.println("Error fetching or processing measurements: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getWeekStart(Instant timestamp) {
        // Define the starting week as Monday, 23rd December 2024
        LocalDateTime startDate = LocalDateTime.of(2024, 12, 23, 0, 0, 0, 0);
        ZoneId zoneId = ZoneId.systemDefault();

        LocalDateTime date = LocalDateTime.ofInstant(timestamp, zoneId);

        // Calculate the start of the week based on the input timestamp
        LocalDateTime weekStart = startDate.plusWeeks(ChronoUnit.WEEKS.between(startDate, date));

        return weekStart.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }


    private String buildQueryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((p1, p2) -> p1 + "&" + p2)
                .orElse("");
    }

    // Helper class to store data points
    static class DataPoint {
        private final Instant timestamp;
        private final double weight;
        private final double fatKg;
        private final double muscleKg;

        public DataPoint(Instant timestamp, double weight, double fatKg, double muscleKg) {
            this.timestamp = timestamp;
            this.weight = weight;
            this.fatKg = fatKg;
            this.muscleKg = muscleKg;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public double getWeight() {
            return weight;
        }

        public double getFatKg() {
            return fatKg;
        }

        public double getMuscleKg() {
            return muscleKg;
        }
    }

    private String readResponse(CloseableHttpResponse response) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line);
        }
        return result.toString();
    }
}
