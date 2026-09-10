
########################################################################################################################
update memory bank
########################################################################################################################
/opsx:explore ──► /opsx:propose ──► /opsx:apply ──► /opsx:sync ──► /opsx:archive
########################################################################################################################
/opsx:archive add-tile-skip-cache-param
archive without sync

########################################################################################################################

/opsx:propose
com.vb.wingfoil.tiles.TileProxyController.getTile add parameter to skip the cache - skipCache = true/false
Call example https://localhost:443/tiles/15/19114/9503.png?skipCache=true

########################################################################################################################

/opsx:propose
Read the /home/vladislav.bondarchuk@rtlabs.ru/Projects/GIT_MY/WindSensor/openspec/changes/fix-osm-tile-compliance/tasks.md
Our goal is to implement the first part here ## 1. Caching tile proxy (backend service behind `serverUrl`) - the backend.
We MUST comply with the following:
Use the correct URL: https://tile.openstreetmap.org/{z}/{x}/{y}.png.
Provide visible licence attribution, following the Attribution Guidelines.
Send a valid HTTP User-Agent that clearly identifies your application (or a platform X-Requested-With app ID where set automatically).
From web pages, ensure a valid HTTP Referer header is sent.
Cache tiles locally according to HTTP caching headers (or at least 7 days if your cache cannot read them).
Avoid encouraging or enabling copyright infringement.
Also read https://osmfoundation.org/wiki/Licence/Attribution_Guidelines

To implement the cache you MUST refer docs https://micronaut-projects.github.io/micronaut-cache/5.1.0/guide/index.html
You MUST use ehcache - cache in the local file system.
The cache folder MUST be mapped in dev_setup/docker-compose.yml

The https://tile.openstreetmap.org must be in resources/application.yml

Also you must implement the integration test that will call real tile generation.
For test use same GPS test coordinates as in /home/vladislav.bondarchuk@rtlabs.ru/Projects/GIT_MY/WindSensor/source/UIRenderer.mc - method getGpsCoordinates
and same {z}/{x}/{y} calculation logic
```
const FIXED_GPS_COORDINATES = [60.068347, 30.002349] as Array;
```


