package com.vb.wingfoil.tiles;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
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

    public TileProxyController(OsmTileService tileService) {
        this.tileService = tileService;
    }

    @Get(uri = "/{z}/{x}/{y}.png", produces = MediaType.IMAGE_PNG)
    @ExecuteOn(TaskExecutors.VIRTUAL)
    @Operation(
            summary = "Fetch a single OpenStreetMap slippy-map tile",
            description =
                    "Caching proxy for one OSM tile ({z}/{x}/{y}). Data © OpenStreetMap contributors, CC-BY-SA. See https://www.openstreetmap.org/copyright.",
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
    public HttpResponse<byte[]> getTile(@PathVariable int z, @PathVariable int x, @PathVariable int y) {
        return serveTile(validateCoordinates(z, x, y));
    }

    private HttpResponse<byte[]> serveTile(TileCoordinate coordinate) {
        try {
            var result = tileService.getTile(coordinate.z(), coordinate.x(), coordinate.y());
            var remainingSeconds = Math.max(0L, (result.expiresAtEpochMillis() - System.currentTimeMillis()) / 1000);
            return HttpResponse.ok(result.png())
                    .contentType(MediaType.IMAGE_PNG)
                    .header("X-Cache", result.status().name())
                    .header("Cache-Control", "public, max-age=" + remainingSeconds);
        } catch (OsmTileService.TileFetchException e) {
            throw new TileProxyException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
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
