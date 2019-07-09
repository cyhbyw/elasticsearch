/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.common.geo.parsers;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoShapeType;
import org.elasticsearch.common.geo.builders.CoordinatesBuilder;
import org.elasticsearch.common.geo.builders.EnvelopeBuilder;
import org.elasticsearch.common.geo.builders.GeometryCollectionBuilder;
import org.elasticsearch.common.geo.builders.LineStringBuilder;
import org.elasticsearch.common.geo.builders.MultiLineStringBuilder;
import org.elasticsearch.common.geo.builders.MultiPointBuilder;
import org.elasticsearch.common.geo.builders.MultiPolygonBuilder;
import org.elasticsearch.common.geo.builders.PointBuilder;
import org.elasticsearch.common.geo.builders.PolygonBuilder;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.BaseGeoShapeFieldMapper;
import org.locationtech.jts.geom.Coordinate;

import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.util.List;

/**
 * Parses shape geometry represented in WKT format
 *
 * complies with OGC® document: 12-063r5 and ISO/IEC 13249-3:2016 standard
 * located at http://docs.opengeospatial.org/is/12-063r5/12-063r5.html
 */
public class GeoWKTParser {
    public static final String EMPTY = "EMPTY";
    public static final String SPACE = Loggers.SPACE;
    public static final String LPAREN = "(";
    public static final String RPAREN = ")";
    public static final String COMMA = ",";
    public static final String NAN = "NaN";

    private static final String NUMBER = "<NUMBER>";
    private static final String EOF = "END-OF-STREAM";
    private static final String EOL = "END-OF-LINE";

    // no instance
    private GeoWKTParser() {}

    public static ShapeBuilder parse(XContentParser parser, final BaseGeoShapeFieldMapper shapeMapper)
            throws IOException, ElasticsearchParseException {
        return parseExpectedType(parser, null, shapeMapper);
    }

    public static ShapeBuilder parseExpectedType(XContentParser parser, final GeoShapeType shapeType)
            throws IOException, ElasticsearchParseException {
        return parseExpectedType(parser, shapeType, null);
    }

    /** throws an exception if the parsed geometry type does not match the expected shape type */
    public static ShapeBuilder parseExpectedType(XContentParser parser, final GeoShapeType shapeType,
                                                 final BaseGeoShapeFieldMapper shapeMapper)
            throws IOException, ElasticsearchParseException {
        Explicit<Boolean> ignoreZValue = (shapeMapper == null) ? BaseGeoShapeFieldMapper.Defaults.IGNORE_Z_VALUE :
            shapeMapper.ignoreZValue();
        Explicit<Boolean> coerce = (shapeMapper == null) ? BaseGeoShapeFieldMapper.Defaults.COERCE : shapeMapper.coerce();
        return parseExpectedType(parser, shapeType, ignoreZValue.value(), coerce.value(), true);
    }

    public static ShapeBuilder parseExpectedType(XContentParser parser, GeoShapeType shapeType, boolean ignoreZValue, boolean coerce, boolean isGeo)
            throws IOException, ElasticsearchParseException {
        try (StringReader reader = new StringReader(parser.text())) {
            // setup the tokenizer; configured to read words w/o numbers
            StreamTokenizer tokenizer = new StreamTokenizer(reader);
            tokenizer.resetSyntax();
            tokenizer.wordChars('a', 'z');
            tokenizer.wordChars('A', 'Z');
            tokenizer.wordChars(128 + 32, 255);
            tokenizer.wordChars('0', '9');
            tokenizer.wordChars('-', '-');
            tokenizer.wordChars('+', '+');
            tokenizer.wordChars('.', '.');
            tokenizer.whitespaceChars(0, ' ');
            tokenizer.commentChar('#');
            ShapeBuilder builder = parseGeometry(tokenizer, shapeType, ignoreZValue, coerce, isGeo);
            checkEOF(tokenizer);
            return builder;
        }
    }

    /** parse geometry from the stream tokenizer */
    protected static ShapeBuilder parseGeometry(StreamTokenizer stream, GeoShapeType shapeType, final boolean ignoreZValue,
                                              final boolean coerce, final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        final GeoShapeType type = GeoShapeType.forName(nextWord(stream));
        if (shapeType != null && shapeType != GeoShapeType.GEOMETRYCOLLECTION) {
            if (type.wktName().equals(shapeType.wktName()) == false) {
                throw new ElasticsearchParseException("Expected geometry type [{}] but found [{}]", shapeType, type);
            }
        }
        switch (type) {
            case POINT:
                return parsePoint(stream, ignoreZValue, coerce, isGeo);
            case MULTIPOINT:
                return parseMultiPoint(stream, ignoreZValue, coerce, isGeo);
            case LINESTRING:
                return parseLine(stream, ignoreZValue, coerce, isGeo);
            case MULTILINESTRING:
                return parseMultiLine(stream, ignoreZValue, coerce, isGeo);
            case POLYGON:
                return parsePolygon(stream, ignoreZValue, coerce, isGeo);
            case MULTIPOLYGON:
                return parseMultiPolygon(stream, ignoreZValue, coerce, isGeo);
            case ENVELOPE:
                return parseBBox(stream, isGeo);
            case GEOMETRYCOLLECTION:
                return parseGeometryCollection(stream, ignoreZValue, coerce, isGeo);
            default:
                throw new IllegalArgumentException("Unknown geometry type: " + type);
        }
    }

    private static EnvelopeBuilder parseBBox(StreamTokenizer stream, final boolean isGeo) throws IOException, ElasticsearchParseException {
        if (nextEmptyOrOpen(stream).equals(EMPTY)) {
            return null;
        }
        double minLon = nextNumber(stream);
        nextComma(stream);
        double maxLon = nextNumber(stream);
        nextComma(stream);
        double maxLat = nextNumber(stream);
        nextComma(stream);
        double minLat = nextNumber(stream);
        nextCloser(stream);
        return new EnvelopeBuilder(new Coordinate(minLon, maxLat), new Coordinate(maxLon, minLat), isGeo);
    }

    private static PointBuilder parsePoint(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce,
                                           final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        if (nextEmptyOrOpen(stream).equals(EMPTY)) {
            return null;
        }
        PointBuilder pt = new PointBuilder(nextNumber(stream), nextNumber(stream), isGeo);
        if (isNumberNext(stream) == true) {
            GeoPoint.assertZValue(ignoreZValue, nextNumber(stream));
        }
        nextCloser(stream);
        return pt;
    }

    private static List<Coordinate> parseCoordinateList(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce)
            throws IOException, ElasticsearchParseException {
        CoordinatesBuilder coordinates = new CoordinatesBuilder();
        boolean isOpenParen = false;
        if (isNumberNext(stream) || (isOpenParen = nextWord(stream).equals(LPAREN))) {
            coordinates.coordinate(parseCoordinate(stream, ignoreZValue, coerce));
        }

        if (isOpenParen && nextCloser(stream).equals(RPAREN) == false) {
            throw new ElasticsearchParseException("expected: [{}]" + RPAREN + " but found: [{}]" + tokenString(stream), stream.lineno());
        }

        while (nextCloserOrComma(stream).equals(COMMA)) {
            isOpenParen = false;
            if (isNumberNext(stream) || (isOpenParen = nextWord(stream).equals(LPAREN))) {
                coordinates.coordinate(parseCoordinate(stream, ignoreZValue, coerce));
            }
            if (isOpenParen && nextCloser(stream).equals(RPAREN) == false) {
                throw new ElasticsearchParseException("expected: " + RPAREN + " but found: " + tokenString(stream), stream.lineno());
            }
        }
        return coordinates.build();
    }

    private static Coordinate parseCoordinate(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce)
            throws IOException, ElasticsearchParseException {
        final double lon = nextNumber(stream);
        final double lat = nextNumber(stream);
        Double z = null;
        if (isNumberNext(stream)) {
            z = GeoPoint.assertZValue(ignoreZValue, nextNumber(stream));
        }
        return z == null ? new Coordinate(lon, lat) : new Coordinate(lon, lat, z);
    }

    private static MultiPointBuilder parseMultiPoint(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce,
                                                     final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        String token = nextEmptyOrOpen(stream);
        if (token.equals(EMPTY)) {
            return new MultiPointBuilder(isGeo);
        }
        return new MultiPointBuilder(parseCoordinateList(stream, ignoreZValue, coerce), isGeo);
    }

    private static LineStringBuilder parseLine(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce,
                                               final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        String token = nextEmptyOrOpen(stream);
        if (token.equals(EMPTY)) {
            return null;
        }
        return new LineStringBuilder(parseCoordinateList(stream, ignoreZValue, coerce), isGeo);
    }

    // A LinearRing is closed LineString with 4 or more positions. The first and last positions
    // are equivalent (they represent equivalent points).
    private static LineStringBuilder parseLinearRing(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce,
                                                     final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        String token = nextEmptyOrOpen(stream);
        if (token.equals(EMPTY)) {
            return null;
        }
        List<Coordinate> coordinates = parseCoordinateList(stream, ignoreZValue, coerce);
        int coordinatesNeeded = coerce ? 3 : 4;
        if (coordinates.size() >= coordinatesNeeded) {
            if (!coordinates.get(0).equals(coordinates.get(coordinates.size() - 1))) {
                if (coerce == true) {
                    coordinates.add(coordinates.get(0));
                } else {
                    throw new ElasticsearchParseException("invalid LinearRing found (coordinates are not closed)");
                }
            }
        }
        if (coordinates.size() < 4) {
            throw new ElasticsearchParseException("invalid number of points in LinearRing (found [{}] - must be >= 4)",
                coordinates.size());
        }
        return new LineStringBuilder(coordinates, isGeo);
    }

    private static MultiLineStringBuilder parseMultiLine(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce,
                                                         final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        String token = nextEmptyOrOpen(stream);
        if (token.equals(EMPTY)) {
            return new MultiLineStringBuilder();
        }
        MultiLineStringBuilder builder = new MultiLineStringBuilder();
        builder.linestring(parseLine(stream, ignoreZValue, coerce, isGeo));
        while (nextCloserOrComma(stream).equals(COMMA)) {
            builder.linestring(parseLine(stream, ignoreZValue, coerce, isGeo));
        }
        return builder;
    }

    private static PolygonBuilder parsePolygon(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce,
                                               final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        if (nextEmptyOrOpen(stream).equals(EMPTY)) {
            return null;
        }
        PolygonBuilder builder = new PolygonBuilder(parseLinearRing(stream, ignoreZValue, coerce, isGeo),
            BaseGeoShapeFieldMapper.Defaults.ORIENTATION.value(), isGeo);
        while (nextCloserOrComma(stream).equals(COMMA)) {
            builder.hole(parseLinearRing(stream, ignoreZValue, coerce, isGeo));
        }
        return builder;
    }

    private static MultiPolygonBuilder parseMultiPolygon(StreamTokenizer stream, final boolean ignoreZValue, final boolean coerce,
                                                         final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        if (nextEmptyOrOpen(stream).equals(EMPTY)) {
            return null;
        }
        MultiPolygonBuilder builder = new MultiPolygonBuilder().polygon(parsePolygon(stream, ignoreZValue, coerce, isGeo));
        while (nextCloserOrComma(stream).equals(COMMA)) {
            builder.polygon(parsePolygon(stream, ignoreZValue, coerce, isGeo));
        }
        return builder;
    }

    private static GeometryCollectionBuilder parseGeometryCollection(StreamTokenizer stream, final boolean ignoreZValue,
                                                                     final boolean coerce, final boolean isGeo)
            throws IOException, ElasticsearchParseException {
        if (nextEmptyOrOpen(stream).equals(EMPTY)) {
            return null;
        }
        GeometryCollectionBuilder builder = new GeometryCollectionBuilder(isGeo).shape(
            parseGeometry(stream, GeoShapeType.GEOMETRYCOLLECTION, ignoreZValue, coerce, isGeo));
        while (nextCloserOrComma(stream).equals(COMMA)) {
            builder.shape(parseGeometry(stream, null, ignoreZValue, coerce, isGeo));
        }
        return builder;
    }

    /** next word in the stream */
    private static String nextWord(StreamTokenizer stream) throws ElasticsearchParseException, IOException {
        switch (stream.nextToken()) {
            case StreamTokenizer.TT_WORD:
                final String word = stream.sval;
                return word.equalsIgnoreCase(EMPTY) ? EMPTY : word;
            case '(': return LPAREN;
            case ')': return RPAREN;
            case ',': return COMMA;
        }
        throw new ElasticsearchParseException("expected word but found: " + tokenString(stream), stream.lineno());
    }

    private static double nextNumber(StreamTokenizer stream) throws IOException, ElasticsearchParseException {
        if (stream.nextToken() == StreamTokenizer.TT_WORD) {
            if (stream.sval.equalsIgnoreCase(NAN)) {
                return Double.NaN;
            } else {
                try {
                    return Double.parseDouble(stream.sval);
                } catch (NumberFormatException e) {
                    throw new ElasticsearchParseException("invalid number found: " + stream.sval, stream.lineno());
                }
            }
        }
        throw new ElasticsearchParseException("expected number but found: " + tokenString(stream), stream.lineno());
    }

    private static String tokenString(StreamTokenizer stream) {
        switch (stream.ttype) {
            case StreamTokenizer.TT_WORD: return stream.sval;
            case StreamTokenizer.TT_EOF: return EOF;
            case StreamTokenizer.TT_EOL: return EOL;
            case StreamTokenizer.TT_NUMBER: return NUMBER;
        }
        return "'" + (char) stream.ttype + "'";
    }

    private static boolean isNumberNext(StreamTokenizer stream) throws IOException {
        final int type = stream.nextToken();
        stream.pushBack();
        return type == StreamTokenizer.TT_WORD;
    }

    private static String nextEmptyOrOpen(StreamTokenizer stream) throws IOException, ElasticsearchParseException {
        final String next = nextWord(stream);
        if (next.equals(EMPTY) || next.equals(LPAREN)) {
            return next;
        }
        throw new ElasticsearchParseException("expected " + EMPTY + " or " + LPAREN
            + " but found: " + tokenString(stream), stream.lineno());
    }

    private static String nextCloser(StreamTokenizer stream) throws IOException, ElasticsearchParseException {
        if (nextWord(stream).equals(RPAREN)) {
            return RPAREN;
        }
        throw new ElasticsearchParseException("expected " + RPAREN + " but found: " + tokenString(stream), stream.lineno());
    }

    private static String nextComma(StreamTokenizer stream) throws IOException, ElasticsearchParseException {
        if (nextWord(stream).equals(COMMA) == true) {
            return COMMA;
        }
        throw new ElasticsearchParseException("expected " + COMMA + " but found: " + tokenString(stream), stream.lineno());
    }

    private static String nextCloserOrComma(StreamTokenizer stream) throws IOException, ElasticsearchParseException {
        String token = nextWord(stream);
        if (token.equals(COMMA) || token.equals(RPAREN)) {
            return token;
        }
        throw new ElasticsearchParseException("expected " + COMMA + " or " + RPAREN
            + " but found: " + tokenString(stream), stream.lineno());
    }

    /** next word in the stream */
    private static void checkEOF(StreamTokenizer stream) throws ElasticsearchParseException, IOException {
        if (stream.nextToken() != StreamTokenizer.TT_EOF) {
            throw new ElasticsearchParseException("expected end of WKT string but found additional text: "
                + tokenString(stream), stream.lineno());
        }
    }
}
