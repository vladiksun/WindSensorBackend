package com.vb.wingfoil.tiles;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Single-tile caching proxy for OpenStreetMap slippy-map tiles. This is the only tile-serving route
 * in the application (no bulk/bbox/multi-tile endpoints), per OSM's tile usage policy.
 */
@Controller("/tiles")
public class TileProxyController {

    public static final int MAX_ZOOM_LEVEL = 20;

    private final OsmTileService tileService;

    private final TileCompositeService compositeService;

    public TileProxyController(OsmTileService tileService, TileCompositeService compositeService) {
        this.tileService = tileService;
        this.compositeService = compositeService;
    }

    @Get(uri = "/{z}/{x}/{y}.png", produces = MediaType.IMAGE_PNG)
    @ExecuteOn(TaskExecutors.VIRTUAL)
    @Operation(
            summary = "Fetch a single OpenStreetMap slippy-map tile",
            description =
                    "Caching proxy for one OSM tile ({z}/{x}/{y}). Data © OpenStreetMap contributors, CC-BY-SA. See https://www.openstreetmap.org/copyright. Optional query parameter skipCache=true forces a fresh upstream fetch (the fetched tile is still cached).",
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Tile served (fresh from upstream or from the local cache)",
                        content =
                                @Content(
                                        mediaType = MediaType.IMAGE_PNG,
                                        schema = @Schema(type = "string", format = "binary"))),
                @ApiResponse(responseCode = "400", description = "Invalid tile coordinates"),
                @ApiResponse(responseCode = "502", description = "Upstream tile source unavailable")
            })
    public HttpResponse<byte[]> getTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @QueryValue(defaultValue = "false")
                    @Parameter(
                            description =
                                    "When true, bypasses the local cache and forces a fresh fetch from the upstream source; the fetched tile is still stored in the local cache so subsequent requests without this parameter can be served from it. Defaults to false.",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    boolean skipCache) {
        return serveTile(validateCoordinates(z, x, y), skipCache);
    }

    @Get(uri = "/composite", produces = MediaType.IMAGE_PNG)
    @ExecuteOn(TaskExecutors.VIRTUAL)
    @Operation(
            summary = "Fetch a single composited OpenStreetMap image covering a viewport",
            description = """
                    Composites the slippy-map tiles covering the requested viewport into one PNG of exactly \
                    width×height pixels centred on (lat, lon) at zoom z. Component tiles are fetched in \
                    parallel; failed tile regions are filled with neutral gray and flagged via X-Partial. \
                    Data © OpenStreetMap contributors, CC-BY-SA. See https://www.openstreetmap.org/copyright. \
                    Optional query parameter skipCache=true bypasses the composite cache (component tile \
                    caches are still consulted).""",
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Composited image served (freshly built or from the composite cache)",
                        content =
                                @Content(
                                        mediaType = MediaType.IMAGE_PNG,
                                        schema = @Schema(type = "string", format = "binary"))),
                @ApiResponse(responseCode = "400", description = "Invalid viewport parameters"),
                @ApiResponse(responseCode = "502", description = "All component tiles failed to load")
            })
    public HttpResponse<byte[]> getComposite(
            @QueryValue int z,
            @QueryValue double lat,
            @QueryValue double lon,
            @QueryValue int width,
            @QueryValue int height,
            @QueryValue(defaultValue = "false")
                    @Parameter(
                            description =
                                    "When true, bypasses the composite cache and rebuilds the image (component tile caches are still consulted). Defaults to false.",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    boolean skipCache) {
        validateCompositeParameters(z, lat, lon, width, height);
        var result = compositeService.compose(z, lat, lon, width, height, skipCache);
        var remainingSeconds = Math.max(0L, (result.expiresAtEpochMillis() - System.currentTimeMillis()) / 1000);
        var response = HttpResponse.ok(result.png())
                .contentType(MediaType.IMAGE_PNG)
                .header("X-Cache", result.status().name())
                .header("Cache-Control", "public, max-age=" + remainingSeconds);
        if (result.partial()) {
            response.header("X-Partial", "true");
        }
        return response;
    }

    private static void validateCompositeParameters(int z, double lat, double lon, int width, int height) {
        if (z < 0 || z > MAX_ZOOM_LEVEL) {
            throw new TileProxyException(HttpStatus.BAD_REQUEST, "zoom level must be between 0 and " + MAX_ZOOM_LEVEL);
        }
        if (width < 1 || width > TileCompositeService.MAX_DIMENSION) {
            throw new TileProxyException(
                    HttpStatus.BAD_REQUEST, "width must be between 1 and " + TileCompositeService.MAX_DIMENSION);
        }
        if (height < 1 || height > TileCompositeService.MAX_DIMENSION) {
            throw new TileProxyException(
                    HttpStatus.BAD_REQUEST, "height must be between 1 and " + TileCompositeService.MAX_DIMENSION);
        }
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            throw new TileProxyException(
                    HttpStatus.BAD_REQUEST, "lat/lon out of range (lat ∈ [-90, 90], lon ∈ [-180, 180])");
        }
    }

    private HttpResponse<byte[]> serveTile(TileCoordinate coordinate, boolean skipCache) {
        return tileService
                .getTile(coordinate.z(), coordinate.x(), coordinate.y(), skipCache)
                .map(result -> {
                    var remainingSeconds =
                            Math.max(0L, (result.expiresAtEpochMillis() - System.currentTimeMillis()) / 1000);
                    return HttpResponse.ok(result.png())
                            .contentType(MediaType.IMAGE_PNG)
                            .header("X-Cache", result.status().name())
                            .header("Cache-Control", "public, max-age=" + remainingSeconds);
                })
                .getOrElseThrow(e -> new TileProxyException(HttpStatus.BAD_GATEWAY, e.getMessage(), e));
    }

    private static TileCoordinate validateCoordinates(int z, int x, int y) {
        if (z < 0 || z > MAX_ZOOM_LEVEL) {
            throw new TileProxyException(HttpStatus.BAD_REQUEST, "zoom level must be between 0 and " + MAX_ZOOM_LEVEL);
        }
        var maxIndex = (1 << z) - 1;
        if (x < 0 || x > maxIndex || y < 0 || y > maxIndex) {
            throw new TileProxyException(
                    HttpStatus.BAD_REQUEST, "tile coordinates must be within [0.." + maxIndex + "] for zoom " + z);
        }
        return new TileCoordinate(z, x, y);
    }

    /** A slippy-map tile coordinate that has passed {@link #validateCoordinates(int, int, int)}. */
    private record TileCoordinate(int z, int x, int y) {}
}
