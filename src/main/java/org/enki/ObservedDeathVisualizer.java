package org.enki;

import com.google.common.collect.Lists;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import tech.units.indriya.quantity.Quantities;

import javax.imageio.ImageIO;
import javax.measure.Quantity;
import javax.measure.quantity.Angle;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.abs;
import static java.lang.Math.PI;
import static org.enki.Statistics.standardDeviation;
import static tech.units.indriya.unit.Units.RADIAN;

/**
 * A tool for visualizing the observed death counts published by CDC.
 *
 * @author Gene McCulley (mcculley@stackframe.com)
 * <p>
 * This code is released under the MIT License.
 */
public class ObservedDeathVisualizer extends JFrame {

    private final String region;
    private final List<DataPoint> data;
    private final LocalDate minDate;
    private final LocalDate maxDate;
    private final Duration duration;
    private final int maxCount;
    private final Map<Integer, Color> yearColors = new HashMap<>();
    private static boolean interpolateColors = false;
    private static boolean interpolateUsingHSB = false;

    public ObservedDeathVisualizer(final String region, final List<DataPoint> data) {
        super(region);
        this.data = data;
        this.region = region;
        maxCount = maxCount(data);
        minDate = minDate(data);
        maxDate = maxDate(data);
        duration = Duration.between(minDate.atStartOfDay(), maxDate.atStartOfDay());

        setSize(1000, 1000);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        yearColors.put(2017, Color.PINK);
        yearColors.put(2018, Color.GRAY);
        yearColors.put(2019, Color.BLUE);
        yearColors.put(2020, Color.RED);
        yearColors.put(2021, Color.GREEN);
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
            final Quantity<Angle> theta = monthDayToAngle(d);
            final PolarCoordinate c = new PolarCoordinate(radius, theta);
            final Point2D p = c.toCartesian(clockwiseRotator);
            g2d.drawLine(0, 0, (int) p.getX(), (int) p.getY());
            final AffineTransform current = g2d.getTransform();
            final AffineTransform newXform = g2d.getTransform();
            newXform.translate(p.getX(), p.getY());
            newXform.rotate(-((i - 1) * (PI * 2 / 12)) + (PI / 2));
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

        plotData(g2d);
    }

    private void drawKey(final Graphics2D g2d) {
        final int height = 25;
        g2d.setStroke(new BasicStroke(5));
        for (int year = minDate.getYear(); year <= maxDate.getYear(); year++) {
            final int y = (year - minDate.getYear()) * height;
            final LocalDate firstDayOfYear = LocalDate.of(year, 1, 1);
            final LocalDate firstColorDate = firstDayOfYear.compareTo(minDate) < 0 ? minDate : firstDayOfYear;
            g2d.setColor(getColor(firstColorDate));
            g2d.drawString(Integer.toString(year), 0, y);
        }

        if (maxDate.compareTo(incompleteDataDate) >= 0) {
            g2d.setStroke(getStroke(maxDate, 5));
            g2d.setColor(getColor(LocalDate.now()));
            g2d.drawString("incomplete data", 0, (maxDate.getYear() - minDate.getYear() + 1) * height);
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

    private Stroke getStroke(final LocalDate date, final float width) {
        if (date.compareTo(incompleteDataDate) >= 0) {
            return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{9},
                    0);
        } else {
            return new BasicStroke(width);
        }
    }

    private static Color setAlpha(final Color c, final float alpha) {
        int red = c.getRed();
        int green = c.getGreen();
        int blue = c.getBlue();
        return new Color(red, green, blue, (int) (alpha * 255.0));
    }

    private static LocalDate incompleteDataDate = LocalDate.now().minusDays(6 * 7);

    private Color getColor(final LocalDate date) {
        final float alpha = date.compareTo(incompleteDataDate) > 0 ? 0.3f : 1;
        final Color base;

        if (interpolateColors) {
            if (interpolateUsingHSB) {
                base = interpolateHSB(endColor, startColor, distanceAlongDuration(date));
            } else {
                base = interpolateRGB(endColor, startColor, distanceAlongDuration(date));
            }
        } else {
            base = yearColors.get(date.getYear());
        }

        return setAlpha(base, alpha);
    }

    private void plotData(final Graphics2D g2d) {
        final int numPoints = data.size();
        for (int i = 1; i < numPoints; i++) {
            final GeneralPath polyline = new GeneralPath(GeneralPath.WIND_EVEN_ODD, 2);
            final DataPoint startDataPoint = data.get(i - 1);
            final Point2D.Double start = toPolar(startDataPoint).toCartesian(clockwiseRotator);
            polyline.moveTo(start.x, start.y);
            final DataPoint dataPoint = data.get(i);
            final Point2D.Double p = toPolar(dataPoint).toCartesian(clockwiseRotator);
            polyline.lineTo(p.x, p.y);
            g2d.setColor(getColor(dataPoint.date));
            g2d.setStroke(getStroke(dataPoint.date, maxCount / 100.0f));
            g2d.draw(polyline);
        }
    }

    private static PolarCoordinate toPolar(final DataPoint p) {
        return new PolarCoordinate(p.count, dateToAngle(p.date));
    }

    private static Quantity<Angle> dateToAngle(final LocalDate d) {
        return Quantities.getQuantity((double) (d.getDayOfYear() - 1) / 366.0 * PI * 2, RADIAN);
    }

    private static Quantity<Angle> monthDayToAngle(final MonthDay d) {
        final Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MONTH, d.getMonthValue() - 1);
        cal.set(Calendar.DAY_OF_MONTH, d.getDayOfMonth());
        final int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        return Quantities.getQuantity((double) (dayOfYear - 1) / 366.0 * PI * 2, RADIAN);
    }

    // Rotate to clockwise with 0 at 12:00.
    private static final Function<Double, Double> clockwiseRotator = theta -> -theta + PI / 2;

    public static class PolarCoordinate {

        public final double r;
        public final double theta; // angle in radians

        public PolarCoordinate(final double r, final Quantity<Angle> theta) {
            this.r = r;
            this.theta = theta.to(RADIAN).getValue().doubleValue();
        }

        public final Point2D.Double toCartesian() {
            return toCartesian(Function.identity());
        }

        public final Point2D.Double toCartesian(final Function<Double, Double> rotator) {
            final double rotatedTheta = rotator.apply(theta);
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
        g2d.drawString("learn more at https://mcculley.github.io/VisualizingObservedDeaths/", 50, 975);
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

        public DataSet(final String region, final List<DataPoint> points) {
            this.region = region;
            this.points = points;
        }

        @Override
        public String toString() {
            return "DataSet{" +
                    "region='" + region + '\'' +
                    ", points=" + points + '}';
        }

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
        final int observedNumberColumn = findHeaderIndex(header, "Observed Number");
        final int typeColumn = findHeaderIndex(header, "Type");
        final List<DataPoint> list = new ArrayList<>();
        final Stream<String[]> nonHeaderLines = lines.stream().skip(1);
        nonHeaderLines.forEach((line) -> {
            final String type = line[typeColumn];

            // Skip rows marked "Predicted". We want only the observed deaths.
            if (type.startsWith("Predicted")) {
                return;
            }

            final String count = line[observedNumberColumn];
            if (count.length() == 0) {
                return;
            }

            final LocalDate date = LocalDate.parse(line[weekEndingColumn]);
            list.add(new DataPoint(date, Integer.parseInt(count)));
        });

        return new DataSet(region, list);
    }

    /**
     * The data we get from CDC is incomplete for the most recent weeks. It apparently takes many weeks for the states
     * to collect death certificates and report them to the CDC. This leads to a graph that looks like things are vastly
     * improved for the most recent weeks, which is misleading. This function trims data which is more than one standard
     * deviation below the previous ten points.
     *
     * @param points a List of DataPoint objects
     * @return a potentially trimmed list of data points
     */
    private static List<DataPoint> trimReportingLag(final List<DataPoint> points) {
        final int lastPoint = points.get(points.size() - 1).count;
        final int nextToLastPoint = points.get(points.size() - 2).count;
        final int numPreviousPoints = 10;
        final List<Integer> previousPoints =
                points.subList(points.size() - (numPreviousPoints + 1), points.size() - 1).stream().map((p) -> p.count)
                        .collect(
                                Collectors.toList());
        final double σ = standardDeviation(previousPoints);
        final double deviation = lastPoint - nextToLastPoint;
        if (deviation < 0 && abs(deviation) > σ) {
            return trimReportingLag(points.subList(0, points.size() - 2));
        } else {
            return points;
        }
    }

    // This is only here because the Java type system cannot resolve T versus T[] with varargs.
    private static <T> List<T> newArrayList(final T o) {
        return Lists.newArrayList(o);
    }

    private static Stream<Map.Entry<String, List<String[]>>> splitRegions(final List<String[]> lines) {
        final String[] header = lines.get(0);
        final int stateColumn = findHeaderIndex(header, "State");
        final Function<String[], String> classifier = (line) -> line[stateColumn];
        final Map<String, List<String[]>> regions = lines.stream().skip(1)
                .collect(Collectors.groupingBy(classifier, Collectors.toCollection(() -> newArrayList(header))));
        return regions.entrySet().stream();
    }

    private static final Color startColor = new Color(255, 128, 0);
    private static final Color endColor = new Color(0, 0, 255);

    public static Color interpolateRGB(final Color endColor, final Color startColor, final double t) {
        if (t < 0 || t > 1) {
            throw new IllegalArgumentException();
        }

        final float inverse = 1.0f - (float) t;
        final int r = (int) (endColor.getRed() * t + startColor.getRed() * inverse);
        final int g = (int) (endColor.getGreen() * t + startColor.getGreen() * inverse);
        final int b = (int) (endColor.getBlue() * t + startColor.getBlue() * inverse);
        return new Color(r, g, b);
    }

    public static Color interpolateHSB(final Color endColor, final Color startColor, final double t) {
        if (t < 0 || t > 1) {
            throw new IllegalArgumentException();
        }

        final float[] startHSB = Color.RGBtoHSB(startColor.getRed(), startColor.getGreen(), startColor.getBlue(), null);
        final float[] endHSB = Color.RGBtoHSB(endColor.getRed(), endColor.getGreen(), endColor.getBlue(), null);

        final float inverse = 1.0f - (float) t;
        final float h = endHSB[0] * (float) t + startHSB[0] * inverse;
        final float s = endHSB[1] * (float) t + startHSB[1] * inverse;
        final float b = endHSB[2] * (float) t + startHSB[2] * inverse;
        return Color.getHSBColor(h, s, b);
    }

    private static String makeFileName(final URL l) {
        final String s = l.toString();
        final StringBuilder buf = new StringBuilder();
        for (final char c : s.toCharArray()) {
            if (c == '/') {
                buf.append('-');
            } else {
                buf.append(c);
            }
        }

        return buf.toString();
    }

    private static InputStream openCachedURL(final URL l) throws IOException {
        final File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        final File cachedFile = new File(tmpDir, makeFileName(l));
        System.err.println("cachedFile=" + cachedFile);
        if (cachedFile.exists()) {
            final BasicFileAttributes attr = Files.readAttributes(cachedFile.toPath(), BasicFileAttributes.class);
            final Duration age = Duration.between(attr.lastModifiedTime().toInstant(), Instant.now());
            if (age.compareTo(Duration.ofDays(1)) < 0) {
                System.err.println("using cached file");
                return new FileInputStream(cachedFile);
            } else {
                System.err.println("cached file is expired. age=" + age);
                cachedFile.delete();
            }
        }

        System.err.println("fetching " + l);
        final InputStream is = l.openStream();
        final byte[] targetArray = ByteStreams.toByteArray(is);
        Files.write(cachedFile.toPath(), targetArray);
        return ByteSource.wrap(targetArray).openStream();
    }

    public static void main(final String[] args) throws IOException, CsvException {
        final URL data =
                new URL("https://data.cdc.gov/api/views/xkkf-xrst/rows.csv?accessType=DOWNLOAD&bom=true&format=true%20target=");
        System.out.println("reading data from " + data);
        final CSVReader csvReader = new CSVReaderBuilder(new InputStreamReader(openCachedURL(data))).build();
        final List<String[]> allLines = csvReader.readAll();
        System.out.println("generating graphs");
        final Stream<Map.Entry<String, List<String[]>>> regionLists = splitRegions(allLines).parallel();
        regionLists.forEach((e) -> {
            final String region = e.getKey();
            final List<String[]> lines = e.getValue();
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
            try {
                ImageIO.write(i, "png", outputfile);
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            }
        });

    }

}