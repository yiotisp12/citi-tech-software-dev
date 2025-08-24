package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.stage.Stage;
import yahoofinance.YahooFinance;
import yahoofinance.Stock;
import yahoofinance.quotes.stock.StockQuote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App extends Application {

    // A simple record to store a tick: timestamp + price
    public record DjiTick(Instant timestamp, BigDecimal price) {}

    // Bounded, thread-safe queue holding the most recent samples
    private static final int QUEUE_CAPACITY = 1024;
    private static final LinkedBlockingDeque<DjiTick> QUEUE = new LinkedBlockingDeque<>(QUEUE_CAPACITY);

    private static final String SYMBOL = "^DJI";
    private static final long POLL_SECONDS = 5L;

    // JavaFX components
    private LineChart<Number, Number> lineChart;
    private XYChart.Series<Number, Number> dataSeries;
    private ScheduledExecutorService scheduler;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // Create axes
        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Time (seconds since start)");
        yAxis.setLabel("DJI Price ($)");

        // Create line chart
        lineChart = new LineChart<>(xAxis, yAxis);
        lineChart.setTitle("Dow Jones Industrial Average - Live Price");
        lineChart.setCreateSymbols(false); // Don't show dots on data points
        lineChart.setLegendVisible(false);

        // Create data series
        dataSeries = new XYChart.Series<>();
        lineChart.getData().add(dataSeries);

        // Create scene and stage
        Scene scene = new Scene(lineChart, 800, 600);
        primaryStage.setTitle("DJI Live Price Monitor");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Start the polling service
        startPolling();

        // Handle window close
        primaryStage.setOnCloseRequest(event -> {
            stopPolling();
            Platform.exit();
            System.exit(0);
        });
    }

    private void startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dji-poller");
            t.setDaemon(true);
            return t;
        });

        long startTime = System.currentTimeMillis();

        // Schedule fetching task
        scheduler.scheduleAtFixedRate(() -> {
            try {
                DjiTick tick = fetchDjiTick();
                
                // Keep the queue bounded: drop oldest if full
                if (!QUEUE.offerLast(tick)) {
                    QUEUE.pollFirst();
                    QUEUE.offerLast(tick);
                }
                
                System.out.printf("DJI @ %s -> %s%n", tick.timestamp(), tick.price());

                // Update chart on JavaFX Application Thread
                Platform.runLater(() -> {
                    updateChart(tick, startTime);
                });

            } catch (Exception e) {
                System.err.println("Failed to fetch DJI price: " + e.getMessage());
            }
        }, 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void updateChart(DjiTick tick, long startTime) {
        // Calculate time in seconds since start
        long timeSeconds = (System.currentTimeMillis() - startTime) / 1000;
        
        // Add new data point
        dataSeries.getData().add(new XYChart.Data<>(timeSeconds, tick.price()));
        
        // Keep only the last 50 data points for better visualization
        if (dataSeries.getData().size() > 50) {
            dataSeries.getData().remove(0);
        }
        
        // Auto-scale the axes
        lineChart.getXAxis().setAutoRanging(true);
        lineChart.getYAxis().setAutoRanging(true);
    }

    private void stopPolling() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Expose the queue if other parts of the app need to read it
    public static LinkedBlockingDeque<DjiTick> getQueue() {
        return QUEUE;
    }

    private static DjiTick fetchDjiTick() throws Exception {
        // Retrieve the stock; refresh quote to bypass cache and get latest
        Stock stock = YahooFinance.get(SYMBOL);
        StockQuote quote = stock.getQuote(true); // true => refresh

        BigDecimal price = quote.getPrice();
        if (price == null) {
            throw new IllegalStateException("Price not available for " + SYMBOL);
        }

        Calendar cal = quote.getLastTradeTime();
        Instant ts = (cal != null) ? cal.toInstant() : Instant.now();

        return new DjiTick(ts, price);
    }
}