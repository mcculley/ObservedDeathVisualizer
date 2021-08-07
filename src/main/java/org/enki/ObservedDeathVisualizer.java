package org.enki;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
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
        final float alpha = date.compareTo(incompleteDataDate) >= 0 ? 0.3f : 1;
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

        /**
         * Create new PolarCoordinate.
         *
         * @param r     the radius of the point
         * @param theta the angle of the point
         */
        public PolarCoordinate(final double r, final Quantity<Angle> theta) {
            this.r = r;
            this.theta = theta.to(RADIAN).getValue().doubleValue();
        }

        /**
         * Convert a PolarCoordinate to a Cartesian coordinate in Point2D.Double.
         *
         * @return a Point2D.Double
         */
        public final Point2D.Double toCartesian() {
            return toCartesian(Function.identity());
        }

        /**
         * Convert a PolarCoordinate to a Cartesian coordinate in Point2D.Double using a transformation for the angle.
         *
         * @param thetaTransformer the Function to apply to the angle
         * @return a Point2D.Double
         */
        public final Point2D.Double toCartesian(final Function<Double, Double> thetaTransformer) {
            final double rotatedTheta = thetaTransformer.apply(theta);
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

    public static class DataLine {

        public final String state;
        public final String type;
        public final int observedNumber;
        public final LocalDate weekEndingDate;

        public DataLine(@CSVParser.CSVHeaderMapping(column = "State") final String state,
                        @CSVParser.CSVHeaderMapping(column = "Type") final String type,
                        @CSVParser.CSVHeaderMapping(column = "Observed Number") final int observedNumber,
                        @CSVParser.CSVHeaderMapping(column = "Week Ending Date") final LocalDate weekEndingDate) {
            this.state = state;
            this.type = type;
            this.observedNumber = observedNumber;
            this.weekEndingDate = weekEndingDate;
        }

    }

    public static class CSVParser<T> {

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        public @interface CSVHeaderMapping {

            String column();

        }

        private final Class<T> c;
        private final Constructor constructor;
        private final Map<String, Integer> headerIndices;
        private final CSVHeaderMapping[] mappings;
        private final Class<?>[] parameterTypes;

        public CSVParser(final Class<T> c, final String[] header) {
            this.c = c;
            this.headerIndices = parseHeader(header);
            constructor = c.getConstructors()[0];
            mappings = mappings(c);
            parameterTypes = constructor.getParameterTypes();
        }

        private static CSVHeaderMapping findCSVHeaderMappingAnnotation(final Annotation[] annotations) {
            for (final Annotation a : annotations) {
                if (a instanceof CSVHeaderMapping) {
                    return (CSVHeaderMapping) a;
                }
            }

            throw new AssertionError("expected a mapping annotation to be present");
        }

        private static CSVHeaderMapping[] mappings(final Class c) {
            final Constructor constructor = c.getConstructors()[0];
            final Annotation[][] a = constructor.getParameterAnnotations();
            final CSVHeaderMapping[] mappings = new CSVHeaderMapping[a.length];
            final ImmutableList.Builder<CSVHeaderMapping> builder = new ImmutableList.Builder<>();
            for (int i = 0; i < a.length; i++) {
                mappings[i] = findCSVHeaderMappingAnnotation(a[i]);
            }

            return mappings;
        }

        private static Map<String, Integer> parseHeader(final String[] header) {
            final ImmutableMap.Builder<String, Integer> b = new ImmutableMap.Builder<>();
            final int length = header.length;
            for (int i = 0; i < length; i++) {
                b.put(normalize(header[i]), i);
            }

            return b.build();
        }

        private T parse(final String[] line) {
            final Object[] arguments = new Object[parameterTypes.length];
            final List<String> valueStrings = new ArrayList<>();
            for (int i = 0; i < mappings.length; i++) {
                final CSVHeaderMapping mapping = mappings[i];
                final String s = line[headerIndices.get(mapping.column())];
                final Class<?> pType = parameterTypes[i];
                if (pType.equals(String.class)) {
                    arguments[i] = s;
                } else if (pType.equals(int.class)) {
                    arguments[i] = s.isEmpty() ? 0 : Integer.parseInt(s);
                } else if (pType.equals(LocalDate.class)) {
                    arguments[i] = LocalDate.parse(s);
                } else {
                    throw new AssertionError("do not know how to map String to " + pType);
                }

                valueStrings.add(line[headerIndices.get(mapping.column())]);
            }

            try {
                return c.cast(constructor.newInstance(arguments));
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        }

    }

    private static Stream<Map.Entry<String, List<DataPoint>>> splitRegions(final String[] header,
                                                                           final List<String[]> lines) {
        final CSVParser<DataLine> p = new CSVParser<>(DataLine.class, header);
        final Function<String[], DataLine> parser = (line) -> p.parse(line);

        // Skip rows marked "Predicted". We want only the observed deaths.
        final Predicate<DataLine> unweighted = (line) -> !line.type.startsWith("Predicted");

        final Predicate<DataLine> hasCount = (line) -> line.observedNumber > 0;

        final Function<DataLine, DataPoint> linetoDataPoint =
                (line) -> new DataPoint(line.weekEndingDate, line.observedNumber);

        final Function<DataLine, String> regionClassifier = (line) -> line.state;
        final Map<String, List<DataPoint>> regions =
                lines.stream().map(parser).filter(unweighted).filter(hasCount)
                        .collect(Collectors.groupingBy(regionClassifier,
                                Collectors.mapping(linetoDataPoint, Collectors.toList())));
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
        final String[] header = allLines.remove(0);
        System.out.println("generating graphs");
        final Stream<Map.Entry<String, List<DataPoint>>> regionLists = splitRegions(header, allLines).parallel();

        regionLists.forEach((e) -> {
            final String region = e.getKey();
            final ObservedDeathVisualizer app = new ObservedDeathVisualizer(region, e.getValue());
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