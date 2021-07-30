package org.enki;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Math.abs;

public class ObservedDeathVisualizer extends JFrame {

    private final String region;
    private final List<DataPoint> data;
    private final LocalDate minDate;
    private final LocalDate maxDate;
    private final Duration duration;

    public ObservedDeathVisualizer(final String region, final List<DataPoint> data) {
        super(region);
        this.data = data;
        this.region = region;
        minDate = minDate(data);
        maxDate = maxDate(data);
        duration = Duration.between(minDate.atStartOfDay(), maxDate.atStartOfDay());

        setSize(1000, 1000);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
    }

    private double distanceAlongDuration(final LocalDate l) {
        final double distance = Duration.between(minDate.atStartOfDay(), l.atStartOfDay()).toDays();
        return distance / duration.toDays();
    }

    void drawMonths(final Graphics2D g2d, final int radius) {
        final Font monthFont = g2d.getFont().deriveFont(15f);
        g2d.setFont(monthFont);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(1));
        for (int i = 1; i <= 12; i++) {
            final MonthDay d = MonthDay.of(i, 1);
            final double theta = monthDayToAngle(d);
            final PolarCoordinate c = new PolarCoordinate(radius, theta);
            final Point2D p = c.toCartesian();
            g2d.drawLine(0, 0, (int) p.getX(), (int) p.getY());
            final AffineTransform current = g2d.getTransform();
            final AffineTransform newXform = g2d.getTransform();
            newXform.translate(p.getX(), p.getY());
            newXform.rotate(-((i - 1) * (Math.PI * 2 / 12)) + (Math.PI / 2));
            newXform.scale(1, -1);
            g2d.setTransform(newXform);
            final String monthName = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            g2d.drawString(monthName, 0, 0);
            g2d.setTransform(current);
        }
    }

    void plot(final Graphics2D g2d) {
        g2d.scale(1, -1);
        drawMonths(g2d, 375);

        final int maxCount = maxCount(data);
        final double scaleConstant = 350;
        final double newScale = scaleConstant / maxCount;
        final double scale = newScale;
        final AffineTransform t = AffineTransform.getScaleInstance(scale, scale);
        g2d.transform(t);
        final int radiusStep;
        if (maxCount > 20000) {
            radiusStep = 10000;
        } else if (maxCount > 5000) {
            radiusStep = 1000;
        } else if (maxCount > 4000) {
            radiusStep = 500;
        } else if (maxCount > 1800) {
            radiusStep = 400;
        } else if (maxCount > 500) {
            radiusStep = 200;
        } else if (maxCount > 200) {
            radiusStep = 50;
        } else {
            radiusStep = 20;
        }

        final int maxRing = maxCount / radiusStep + 1;

        final Font countFont = g2d.getFont().deriveFont(maxCount / 50.0f);
        g2d.setFont(countFont);
        for (int i = 1; i <= maxRing; i++) {
            final int x = i * -radiusStep;
            final int y = x;
            final int width = i * 2 * radiusStep;
            final int height = width;
            g2d.setStroke(new BasicStroke(maxCount / 600.0f));
            g2d.drawOval(x, y, width, height);
            final int count = radiusStep * i;
            final AffineTransform current = g2d.getTransform();
            final AffineTransform newXform = g2d.getTransform();
            newXform.translate(count, 0);
            newXform.scale(1, -1);
            g2d.setTransform(newXform);
            g2d.drawString(NumberFormat.getInstance().format(count), maxCount / 60, maxCount / 30);
            g2d.setTransform(current);
        }

        plotData(g2d, data, maxCount);
    }

    private void drawKey(final Graphics2D g2d) {
        for (int year = minDate.getYear(); year <= maxDate.getYear(); year++) {
            final int y = (year - minDate.getYear()) * 25;
            final LocalDate firstDayOfYear = LocalDate.of(year, 1, 1);
            final LocalDate firstColorDate = firstDayOfYear.compareTo(minDate) < 0 ? minDate : firstDayOfYear;
            g2d.setColor(getColor(firstColorDate));
            g2d.drawString(Integer.toString(year), 0, y);
            g2d.setStroke(new BasicStroke(5));
        }
    }

    private static LocalDate minDate(final List<DataPoint> points) {
        return points.stream().map((p) -> p.date).min(Comparator.naturalOrder()).get();
    }

    private static LocalDate maxDate(final List<DataPoint> points) {
        return points.stream().map((p) -> p.date).max(Comparator.naturalOrder()).get();
    }

    private static int maxCount(final List<DataPoint> points) {
        return points.stream().mapToInt((p) -> p.count).max().getAsInt();
    }

    private Color getColor(final LocalDate date) {
        return interpolate(endColor, startColor, distanceAlongDuration(date));
    }

    private void plotData(final Graphics2D g2d, final List<DataPoint> points, final int maxCount) {
        final int numPoints = points.size();
        for (int i = 1; i < numPoints; i++) {
            final GeneralPath polyline = new GeneralPath(GeneralPath.WIND_EVEN_ODD, 2);
            final DataPoint startDataPoint = points.get(i - 1);
            final Point2D.Double start = toPolar(startDataPoint).toCartesian();
            polyline.moveTo(start.x, start.y);
            final DataPoint dataPoint = points.get(i);
            final Point2D.Double p = toPolar(dataPoint).toCartesian();
            polyline.lineTo(p.x, p.y);
            g2d.setColor(getColor(dataPoint.date));
            g2d.setStroke(new BasicStroke(maxCount / 100.0f));
            g2d.draw(polyline);
        }
    }

    private static PolarCoordinate toPolar(final DataPoint p) {
        return new PolarCoordinate(p.count, dateToAngle(p.date));
    }

    private static double dateToAngle(final LocalDate d) {
        return (double) (d.getDayOfYear() - 1) / 366.0 * Math.PI * 2;
    }

    private static double monthDayToAngle(final MonthDay d) {
        final Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MONTH, d.getMonthValue() - 1);
        cal.set(Calendar.DAY_OF_MONTH, d.getDayOfMonth());
        final int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        return (double) (dayOfYear - 1) / 366.0 * Math.PI * 2;
    }

    public static class PolarCoordinate {

        public final double r;
        public final double theta;

        public PolarCoordinate(final double r, final double theta) {
            this.r = r;
            this.theta = theta;
        }

        public final Point2D.Double toCartesian() {
            final double rotatedTheta = -theta + Math.PI / 2; // Rotate to clockwise with 0 at 12:00.
            final double x = r * Math.cos(rotatedTheta);
            final double y = r * Math.sin(rotatedTheta);
            return new Point2D.Double(x, y);
        }

        @Override
        public String toString() {
            return "PolarCoordinate{r=" + r + ", theta=" + theta + '}';
        }

    }

    public void paint(final Graphics g) {
        super.paint(g);
        final Graphics2D g2d = (Graphics2D) g;
        g2d.setBackground(Color.WHITE);
        g2d.setColor(Color.BLACK);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawString(String.format("Observed Deaths, %s, All Causes, By Week, ", region) + minDate + " - " + maxDate,
                50, 50);
        g2d.drawString("data retrieved from cdc.gov on " + LocalDate.now(), 50, 950);
        final int x = 700;
        int y = 950;
        final int lineSize = 12;
        g2d.drawString("Feedback and suggestions for improvement:", x, y);
        y += lineSize;
        g2d.drawString("https://twitter.com/mcculley", x, y);
        y += lineSize;
        g2d.drawString("https://linkedin.com/in/mcculley", x, y);
        y += lineSize;
        g2d.drawString("mcculley@stackframe.com", x, y);
        y += lineSize;
        final AffineTransform c = g2d.getTransform();
        g2d.translate(50, 100);
        drawKey(g2d);
        g2d.setTransform(c);
        g.translate(getSize().width / 2, getSize().height / 2);
        plot(g2d);
    }

    public static class DataPoint {

        public final LocalDate date;
        public final int count;

        public DataPoint(final LocalDate date, final int count) {
            this.date = date;
            this.count = count;
        }

        @Override
        public String toString() {
            return "DataPoint{" +
                    "date=" + date +
                    ", count=" + count +
                    '}';
        }

    }

    public static class DataSet {

        public final String region;
        public final List<DataPoint> points;

        public DataSet(String region, List<DataPoint> points) {
            this.region = region;
            this.points = points;
        }

        @Override
        public String toString() {
            return "DataSet{" +
                    "region='" + region + '\'' +
                    ", points=" + points +
                    '}';
        }

    }

    private static double mean(final int[] d) {
        double total = 0;
        for (final double v : d) {
            total += v;
        }

        return total / d.length;
    }

    private static double standardDeviation(final IntStream s) {
        final int[] values = s.toArray();
        double sumOfSquaresOfDistance = 0.0;
        final double mean = mean(values);
        final int length = values.length;
        for (final double num : values) {
            sumOfSquaresOfDistance += Math.pow(num - mean, 2);
        }

        return Math.sqrt(sumOfSquaresOfDistance / length);
    }

    private static String normalize(final String s) {
        // get rid of UTF-8 noise
        final int length = s.length();
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < length; i++) {
            final char c = s.charAt(i);
            if (c == '\uFEFF') {
                continue;
            }

            buf.append(c);
        }

        return buf.toString().strip();
    }

    private static int findHeaderIndex(final String[] header, final String columnName) {
        int index = 0;
        for (final String s : header) {
            final String normalized = normalize(s);
            if (normalized.equals(columnName)) {
                return index;
            }

            index++;
        }

        throw new IllegalArgumentException("could not find " + columnName);
    }

    public static DataSet parseDataSet(final String region, final List<String[]> lines) {
        final String[] header = lines.get(0);
        final int weekEndingColumn = findHeaderIndex(header, "Week Ending Date");
        final int stateColumn = findHeaderIndex(header, "State");
        final int observedNumberColumn = findHeaderIndex(header, "Observed Number");
        final int typeColumn = findHeaderIndex(header, "Type");
        final List<DataPoint> list = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            final String[] line = lines.get(i);
            final String type = line[typeColumn];

            // Skip rows marked "Predicted". We want only the observed deaths.
            if (type.startsWith("Predicted")) {
                continue;
            }

            final String state = line[stateColumn];
            if (!state.equals(region)) {
                continue;
            }

            final String date = line[weekEndingColumn];
            final String count = line[observedNumberColumn];
            final LocalDate localDate = LocalDate.parse(date);
            final Duration age = Duration.between(localDate.atStartOfDay(), LocalDate.now().atStartOfDay());

            if (count.length() == 0) {
                continue;
            }

            list.add(new DataPoint(localDate, Integer.parseInt(count)));
        }

        return new DataSet(region, trimReportingLag(list));
    }

    private static List<DataPoint> trimReportingLag(final List<DataPoint> points) {
        final int lastPoint = points.get(points.size() - 1).count;
        final int nextToLastPoint = points.get(points.size() - 2).count;
        final int numPreviousPoints = 10;
        final List<Integer> previousPoints =
                points.subList(points.size() - (numPreviousPoints + 1), points.size() - 1).stream().map((p) -> p.count)
                        .collect(
                                Collectors.toList());
        final double σ = standardDeviation(previousPoints.stream().mapToInt(Integer::intValue));
        final double deviation = lastPoint - nextToLastPoint;
        if (deviation < 0 && abs(deviation) > σ) {
            return trimReportingLag(points.subList(0, points.size() - 2));
        } else {
            return points;
        }
    }

    public static Set<String> regions(final List<String[]> lines) {
        final Set<String> regions = new HashSet<>();
        final String[] header = lines.get(0);
        final int stateColumn = findHeaderIndex(header, "State");
        for (int i = 1; i < lines.size(); i++) {
            final String[] line = lines.get(i);
            final String state = line[stateColumn];
            regions.add(state);
        }

        return regions;
    }

    private static final Color startColor = new Color(255, 0, 0);
    private static final Color endColor = new Color(0, 0, 255);

    public static Color interpolate(final Color endColor, final Color startColor, final double t) {
        if (t < 0 || t > 1) {
            throw new IllegalArgumentException();
        }

        final double inverse = 1.0 - t;
        final int r = (int) (endColor.getRed() * t + startColor.getRed() * inverse);
        final int g = (int) (endColor.getGreen() * t + startColor.getGreen() * inverse);
        final int b = (int) (endColor.getBlue() * t + startColor.getBlue() * inverse);
        return new Color(r, g, b);
    }

    public static void main(final String[] args) throws IOException, CsvException {
        final URL data =
                new URL("https://data.cdc.gov/api/views/xkkf-xrst/rows.csv?accessType=DOWNLOAD&bom=true&format=true%20target=");
        System.out.println("reading data from " + data);
        final CSVReader csvReader = new CSVReaderBuilder(new InputStreamReader(data.openStream())).build();
        final List<String[]> lines = csvReader.readAll();
        System.out.println("generating graphs");
        final Set<String> regions = regions(lines);
        for (final String region : regions) {
            final DataSet dataSet = parseDataSet(region, lines);
            final ObservedDeathVisualizer app = new ObservedDeathVisualizer(region, dataSet.points);
            SwingUtilities.invokeLater(() -> app.setVisible(true));

            final BufferedImage i = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
            final Graphics2D g = i.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            final Rectangle imageBounds = new Rectangle(0, 0, i.getWidth(), i.getHeight());
            g.setPaint(Color.WHITE);
            g.fill(imageBounds);

            app.paint(g);
            final File outputfile = new File((region + ".png").replaceAll("\\s", ""));
            ImageIO.write(i, "png", outputfile);
        }
    }

}