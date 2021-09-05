package org.enki.odv;

import com.google.common.base.Converter;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import org.enki.CSVParser;
import org.enki.CacheUtilities;
import org.enki.ColorUtilities;
import org.enki.PolarCoordinate;
import tech.units.indriya.quantity.Quantities;

import javax.imageio.ImageIO;
import javax.measure.Quantity;
import javax.measure.quantity.Angle;
import javax.swing.*;
import javax.xml.crypto.Data;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URL;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.PI;
import static java.lang.Math.pow;
import static java.lang.Math.sqrt;
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

    public ObservedDeathVisualizer(final Map<String, Integer> census, final String region, final List<DataPoint> data) {
        super(region);
        this.data = data;
        this.region = region;
        maxCount = maxCount(data);
        minDate = minDate(data);
        maxDate = maxDate(data);
        duration = Duration.between(minDate.atStartOfDay(), maxDate.atStartOfDay());

        final int size = 1000;
        setSize(size, size);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        yearColors.put(2017, Color.PINK);
        yearColors.put(2018, Color.GRAY);
        yearColors.put(2019, Color.BLUE);
        yearColors.put(2020, Color.RED);
        yearColors.put(2021, Color.GREEN);

        dumpStatistics(census, region, data);
    }

    private synchronized static void dumpStatistics(final Map<String, Integer> census, final String region,
                                                    final List<DataPoint> data) {
        final List<DataPoint> filteredOutRemainder =
                data.stream().filter((p) -> p.date.getMonthValue() <= 8).toList();
        final List<DataPoint> d = data;
        final Map<Integer, Integer> deathsByYear = d.stream()
                .collect(Collectors.groupingBy((p) -> p.date.getYear(), Collectors.summingInt((p) -> p.count)));
        final Map<Integer, Double> change = new HashMap<>();
        for (int i = 2018; i <= 2021; i++) {
            change.put(i,
                    (((double) (deathsByYear.get(i) - deathsByYear.get(i - 1))) /
                            (double) deathsByYear.get(i - 1)));
        }

        System.err.printf(region + ":\n");
        System.err.printf("2017 %d\n", deathsByYear.get(2017));
        change.entrySet()
                .forEach((e) -> System.err.printf("%d %d %.2f%%\n", e.getKey(), deathsByYear.get(e.getKey()),
                        e.getValue() * 100));

        final DataPoint maxKilled = data.stream().max(Comparator.comparingInt(o -> o.count)).get();
        System.err.printf("week with most deaths: %s (%d)\n", maxKilled.date, maxKilled.count);

        System.err.println(data.stream().sorted(Comparator.comparingInt(o -> o.count)).toList());
        System.err.printf("\n");
    }

    private static final DataPoint lastGoodDataPoint(final List<DataPoint> l) {
        return l.stream().filter((e) -> e.date.compareTo(incompleteDataDate) <= 0)
                .sorted(Comparator.comparing(DataPoint::getDate).reversed()).findFirst().get();
    }

    private double distanceAlongDuration(final LocalDate l) {
        final double distance = Duration.between(minDate.atStartOfDay(), l.atStartOfDay()).toDays();
        return distance / duration.toDays();
    }

    void drawMonths(final Graphics2D g2d, final float radius) {
        final Font monthFont = g2d.getFont().deriveFont(15.0f);
        g2d.setFont(monthFont);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(1.0f));
        for (int i = 1; i <= 12; i++) {
            final MonthDay d = MonthDay.of(i, 1);
            final Quantity<Angle> theta = monthDayToAngle(d);
            final PolarCoordinate c = new PolarCoordinate(radius, theta);
            final Point2D p = c.toCartesian(Function.identity(), clockwiseRotator);
            g2d.drawLine(0, 0, (int) p.getX(), (int) p.getY());
            final AffineTransform current = g2d.getTransform();
            final AffineTransform newXform = g2d.getTransform();
            newXform.translate(p.getX(), p.getY());
            newXform.rotate(-((i - 1.0f) * PI / 6.0f));
            newXform.scale(1.0f, -1.0f);
            g2d.setTransform(newXform);
            final String monthName = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            final int stringWidth = g2d.getFontMetrics().stringWidth(monthName);
            g2d.drawString(monthName, -((float) stringWidth / 2.0f), -5.0f);
            g2d.setTransform(current);
        }
    }

    void plot(final Graphics2D g2d) {
        final float upperLimit = (g2d.getClipBounds().width / 2.0f) * 0.80f;
        g2d.scale(1.0f, -1.0f);
        drawMonths(g2d, upperLimit);

        final float scaleConstant = (float) radiusTransformer.reverse().convert(upperLimit * 0.90).doubleValue();
        final float scale = scale(scaleConstant / maxCount);
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

        for (float i = 1; i <= maxRing; i++) {
            final float radius = scale(i * radiusStep);
            final float x = -radius;
            final float y = x;
            final float width = 2.0f * radius;
            final float height = width;
            final float strokeWidth = 1.0f / scale;
            g2d.setStroke(new BasicStroke(strokeWidth));
            g2d.draw(new Arc2D.Double(x, y, width, height, 0, 360, Arc2D.CHORD));
            final float count = radiusStep * i;
            final AffineTransform current = g2d.getTransform();
            final AffineTransform newXform = g2d.getTransform();
            newXform.rotate(-(i * PI / 6.0f - 7.0f * PI / 12.0f));
            newXform.scale(1.0f, -1.0f);
            newXform.translate(radius, 0.0f);
            newXform.rotate(PI / 2.0f);
            newXform.scale(1.0f / scale, 1.0f / scale);
            g2d.setTransform(newXform);
            final String countFormatted = NumberFormat.getInstance().format(count);
            final int stringWidth = g2d.getFontMetrics().stringWidth(countFormatted);
            g2d.drawString(countFormatted, -(stringWidth / 2.0f), -5.0f);
            g2d.setTransform(current);
        }

        plotData(g2d, scale);
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

    private static LocalDate incompleteDataDate = LocalDate.now().minusDays(6 * 7);

    private Color getColor(final LocalDate date) {
        final float alpha = date.compareTo(incompleteDataDate) >= 0 ? 0.3f : 1;
        final Color base = yearColors.get(date.getYear());
        return ColorUtilities.setAlpha(base, alpha);
    }

    private void plotData(final Graphics2D g2d, final float scale) {
        final int numPoints = data.size();
        for (int i = 1; i < numPoints; i++) {
            final GeneralPath polyline = new GeneralPath(GeneralPath.WIND_EVEN_ODD, 2);
            final DataPoint startDataPoint = data.get(i - 1);
            final Point2D.Double start = toPolar(startDataPoint).toCartesian(radiusTransformer, clockwiseRotator);
            polyline.moveTo(start.x, start.y);
            final DataPoint dataPoint = data.get(i);
            final Point2D.Double p = toPolar(dataPoint).toCartesian(radiusTransformer, clockwiseRotator);
            polyline.lineTo(p.x, p.y);
            g2d.setColor(getColor(dataPoint.date));
            final float strokeWidth = 4 / scale;
            g2d.setStroke(getStroke(dataPoint.date, strokeWidth));
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

    private static final Converter<Double, Double> linearConverter = Converter.identity();
    private static final Converter<Double, Double> squareRootConverter = new Converter<>() {

        @Override
        protected Double doForward(final Double x) {
            return sqrt(x);
        }

        @Override
        protected Double doBackward(Double x) {
            return pow(x, 2);
        }

    };

    private static final Converter<Double, Double> radiusTransformer = squareRootConverter;

    // Rotate to clockwise with 0 at 12:00.
    private static final Function<Double, Double> clockwiseRotator = theta -> -theta + PI / 2;

    private static float scale(final float f) {
        return (float) radiusTransformer.convert((double) f).doubleValue();
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

        public LocalDate getDate() {
            return date;
        }

        public int getCount() {
            return count;
        }

        @Override
        public String toString() {
            return "DataPoint{" +
                    "date=" + date +
                    ", count=" + count +
                    '}';
        }

    }

    public static class DataLine {

        public final String state;
        public final String type;
        public final int observedNumber;
        public final LocalDate weekEndingDate;

        public DataLine(@CSVParser.Column(name = "State") final String state,
                        @CSVParser.Column(name = "Type") final String type,
                        @CSVParser.Column(name = "Observed Number") final int observedNumber,
                        @CSVParser.Column(name = "Week Ending Date") final LocalDate weekEndingDate) {
            this.state = state;
            this.type = type;
            this.observedNumber = observedNumber;
            this.weekEndingDate = weekEndingDate;
        }

    }

    public static class CensusLine {

        public final String region;
        public final int population;

        public CensusLine(@CSVParser.Column(name = "Region") final String region,
                          @CSVParser.Column(name = "Population") final int population) {
            this.region = region;
            this.population = population;
        }

        public String getRegion() {
            return region;
        }

        public int getPopulation() {
            return population;
        }

    }

    private static Stream<Map.Entry<String, List<DataPoint>>> splitRegions(final String[] header,
                                                                           final List<String[]> lines) {
        // Special parsing for the "Observed Number" column: It sometimes contains empty strings. Set those to 0 and
        // filter them out.
        final Map<String, Function<String, Object>> columnParsers =
                Map.of("Observed Number", (s) -> s.isEmpty() ? 0 : Integer.parseInt(s));

        final CSVParser<DataLine> p =
                new CSVParser.Builder<>(DataLine.class, header).withColumnParsers(columnParsers).build();

        // Skip rows marked "Predicted". We want only the observed deaths.
        final Predicate<DataLine> unweighted = (line) -> !line.type.startsWith("Predicted");

        final Predicate<DataLine> hasCount = (line) -> line.observedNumber > 0;

        final Function<DataLine, DataPoint> lineToDataPoint =
                (line) -> new DataPoint(line.weekEndingDate, line.observedNumber);

        final Function<DataLine, String> regionClassifier = (line) -> line.state;
        final Map<String, List<DataPoint>> regions =
                lines.stream().map(p).filter(unweighted).filter(hasCount)
                        .collect(Collectors.groupingBy(regionClassifier,
                                Collectors.mapping(lineToDataPoint, Collectors.toList())));
        return regions.entrySet().stream();
    }

    private static Map<String, Integer> parseCensus() throws IOException, CsvException {
        final URL censusLocation = ObservedDeathVisualizer.class.getResource("/census-2020.csv");
        final CSVReader censusCSVReader =
                new CSVReaderBuilder(new InputStreamReader(censusLocation.openStream())).build();
        final List<String[]> censusLines = censusCSVReader.readAll();
        final String[] censusHeader = censusLines.remove(0);
        final CSVParser<CensusLine> p = new CSVParser.Builder<>(CensusLine.class, censusHeader).build();
        return censusLines.stream().map(p).collect(Collectors.toMap(CensusLine::getRegion, CensusLine::getPopulation));
    }

    private static Map<String, List<DataPoint>> mergeNYC(final Map<String, List<DataPoint>> map) {
        final Map<String, List<DataPoint>> merged = new HashMap<>(map);
        final List<DataPoint> nyc = merged.remove("New York City");
        final List<DataPoint> ny = merged.remove("New York");
        final Map<LocalDate, List<DataPoint>> byDate =
                Stream.concat(nyc.stream(), ny.stream()).collect(Collectors.groupingBy(DataPoint::getDate));
        final List<DataPoint> reduced = new ArrayList<>();
        for (final List<DataPoint> l : byDate.values()) {
            final DataPoint first = l.get(0);
            final DataPoint second = l.get(1);
            final DataPoint n = new DataPoint(first.date, first.count + second.count);
            reduced.add(n);
        }

        merged.put("New York", reduced.stream().sorted(Comparator.comparing(DataPoint::getDate)).toList());
        return merged;
    }

    private static void dumpPerCapitaStatistics(final Map<String, Integer> census,
                                                final Map<String, List<DataPoint>> regionData) throws IOException {
        final Map<String, List<DataPoint>> merged = mergeNYC(regionData);
        final LocalDate latestGoodDataDate = merged.values().stream().map((v) -> lastGoodDataPoint(v))
                .sorted(Comparator.comparing(DataPoint::getDate).reversed()).findFirst().get().date;
        final Map<String, Optional<DataPoint>> lastGoodDataPoint = merged.entrySet().stream().collect(
                Collectors.toMap((e) -> e.getKey(),
                        (e) -> e.getValue().stream().filter((x) -> x.date.compareTo(latestGoodDataDate) == 0)
                                .findFirst()));
        final Map<String, Integer> deathCount = lastGoodDataPoint.entrySet().stream()
                .collect(Collectors.toMap((e) -> e.getKey(), (e) -> {
                    final Optional<DataPoint> p = e.getValue();
                    return p.isPresent() ? p.get().count : 0;
                }));

        final Map<String, Double> deathPerCapita = deathCount.entrySet().stream()
                .collect(Collectors.toMap((e) -> e.getKey(), (e) -> e.getValue() / (double) census.get(e.getKey())));

        final Map<String, Double> deathPerCapitaSorted = sortByValue(deathPerCapita, Comparator.reverseOrder());

        final int unit = 100000;
        System.out.printf("%s deaths per %s people per week\n", latestGoodDataDate,
                NumberFormat.getInstance().format(unit));
        int count = 1;
        for (final Map.Entry<String, Double> e : deathPerCapitaSorted.entrySet()) {
            System.out.printf("%d: %s %.2f (%s deaths in population of %s)\n", count++, e.getKey(), e.getValue() * unit,
                    NumberFormat.getInstance().format(deathCount.get(e.getKey())),
                    NumberFormat.getInstance().format(census.get(e.getKey())));
        }

        writeCSV(census, merged);
        writeCSVTriples(census, merged);

    }

    private static Map<String, Integer> excessDeaths(final Map<String, List<DataPoint>> regionData) {
        final Map<String, Integer> r = new HashMap<>();
        final Predicate<DataPoint> is2019 = (p) -> p.date.getYear() == 2019;
        final Map<String, Integer> maxDeaths2019 = regionData.entrySet().stream().collect(
                Collectors.toMap((e) -> e.getKey(),
                        (e) -> e.getValue().stream().filter(is2019).mapToInt((i) -> i.count).max().getAsInt()));
        for (final String region : regionData.keySet()) {
            final Stream<DataPoint> deathsAfter2019 =
                    regionData.get(region).stream().filter((i) -> i.date.compareTo(LocalDate.parse("2020-01-01")) >= 0);
            r.put(region, deathsAfter2019.mapToInt((i) -> i.count - maxDeaths2019.get(region)).sum());
        }

        return r;
    }

    private static void writeCSV(final Map<String, Integer> census, final Map<String, List<DataPoint>> data)
            throws IOException {
        final int unit = 100000;
        final Set<LocalDate> uniqueDates =
                data.values().stream().flatMap(List::stream).map((e) -> e.date).collect(Collectors.toSet());
        final LocalDate start = LocalDate.parse("2020-01-01");

        final Set<LocalDate> filteredDates =
                uniqueDates.stream().filter((d) -> d.compareTo(start) >= 0).collect(
                        Collectors.toSet());

        final List<LocalDate> sortedDates = filteredDates.stream().sorted().toList();

        final File outFile = new File("DeathsPer" + unit + ".csv");
        final Writer w = new FileWriter(outFile);
        w.write("Week,");
        w.write(String.join(",", data.keySet()));
        w.write('\n');

        for (final LocalDate date : sortedDates) {
            w.write(date.toString());
            w.write(',');

            for (final String region : data.keySet()) {
                final double population = (double) census.get(region);
                final Optional<DataPoint> d =
                        data.get(region).stream().filter((e) -> e.date.compareTo(date) == 0).findFirst();
                if (d.isPresent()) {
                    double dpc = d.get().count / population * unit;
                    w.write(String.format("%.2f", dpc));
                } else {
                    w.write("0");
                }

                w.write(',');
            }

            w.write('\n');
        }

        w.flush();
        w.close();
    }

    private static void writeCSVTriples(final Map<String, Integer> census, final Map<String, List<DataPoint>> data)
            throws IOException {
        final int unit = 100000;
        final LocalDate start = LocalDate.parse("2020-01-01");

        final File outFile = new File("DeathsPer" + unit + "-triples.csv");
        final Writer w = new FileWriter(outFile);
        w.write("Region,Week,Ratio\n");

        for (final Map.Entry<String, List<DataPoint>> e : data.entrySet()) {
            final String region = e.getKey();
            final double population = (double) census.get(region);
            for (final DataPoint p : e.getValue()) {
                if (p.date.compareTo(start) >= 0) {
                    w.write(region);
                    w.write(',');
                    w.write(p.date.toString());
                    w.write(',');
                    double dpc = p.count / population * unit;
                    w.write(String.format("%.2f", dpc));
                    w.write('\n');
                }
            }
        }

        w.flush();
        w.close();
    }

    private static <T, K, U> Collector<T, ?, Map<K, U>> toLinkedHashMap(
            final Function<? super T, ? extends K> keyMapper,
            final Function<? super T, ? extends U> valueMapper) {
        return Collectors.toMap(keyMapper, valueMapper, (u, v) -> {
            throw new IllegalStateException(String.format("Duplicate key %s", u));
        }, LinkedHashMap::new);
    }

    private static <K, V> Map<K, V> sortByValue(final Map<K, V> map, final Comparator<V> c) {
        return map.entrySet().stream().sorted((o1, o2) -> c.compare(o1.getValue(), o2.getValue()))
                .collect(toLinkedHashMap((e) -> e.getKey(), (e) -> e.getValue()));
    }

    private static <K, V> Map<K, V> sortByValue(final Map<K, V> map) {
        final Comparator<V> c = (Comparator<V>) Comparator.naturalOrder();
        return sortByValue(map, c);
    }

    public static void main(final String[] args) throws IOException, CsvException {
        final Map<String, Integer> census = parseCensus();
        System.err.println("census=" + census);

        final URL data =
                new URL("https://data.cdc.gov/api/views/xkkf-xrst/rows.csv?accessType=DOWNLOAD&bom=true&format=true%20target=");
        System.out.println("reading data from " + data);
        final CSVReader csvReader =
                new CSVReaderBuilder(new InputStreamReader(CacheUtilities.openCachedURL(data))).build();
        final List<String[]> allLines = csvReader.readAll();
        final String[] header = allLines.remove(0);
        System.out.println("generating graphs");
        final Stream<Map.Entry<String, List<DataPoint>>> regionLists = splitRegions(header, allLines).parallel();
        final Map<String, List<DataPoint>> regionData = new HashMap<>();
        regionLists.forEach((e) -> {
            regionData.put(e.getKey(), e.getValue());
            final String region = e.getKey();
            final ObservedDeathVisualizer app = new ObservedDeathVisualizer(census, region, e.getValue());
            SwingUtilities.invokeLater(() -> app.setVisible(true));

            final int size = 1000;
            final BufferedImage i = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            final Graphics2D g = i.createGraphics();
            g.clipRect(0, 0, size, size);
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

        dumpPerCapitaStatistics(census, regionData);
        System.out.println();

        final Map<String, Integer> excessDeathsByRegion = excessDeaths(regionData);

        final Map<String, Integer> sortedByDeaths = sortByValue(excessDeathsByRegion, Comparator.reverseOrder());
        System.out.println("Excess Deaths:");
        for (final Map.Entry<String, Integer> e : sortedByDeaths.entrySet()) {
            System.out.println(e.getKey() + ": " + e.getValue());
        }
    }

}