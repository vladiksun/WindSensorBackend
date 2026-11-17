
########################################################################################################################
update memory bank
########################################################################################################################
/opsx:explore ──► /opsx:propose ──► /opsx:apply ──► /opsx:sync ──► /opsx:archive
########################################################################################################################
/opsx:archive fix-onechip-timestamp-derivation
archive without sync 


########################################################################################################################

/opsx:propose
The [OneChipDataProvider.java](src/main/java/com/vb/wingfoil/provider/impl/OneChipDataProvider.java) does not work properly.
For example https://1chip.ru/windt.php?id=0264 contains data but when I use configuration with this sensor client watch part does
not show any data ( D:\development\Projects\GIT_MY\WindSensor )
Configuration is here https://github.com/vladiksun/WindSensorConfig/blob/e91c61f1d5a9eaaff775ec1066a3d44c93b28c8f/spots.json
Same issue is for https://1chip.ru/windt.php?id=0001

########################################################################################################################

Investigate the page https://1chip.ru/wind.html
I need to scan the drop down list 
```adlanguage
<select name="Spot" id="produce"
```
and get all spots that contain "Dahab"
For each "Dahab" spot i need to constuct the link like
```adlanguage
https://1chip.ru/windt.php?id=0000
```
Where 0000 part is taken from
```adlanguage
<option value="0000,00.000000,00.000000,1794750431,wb00d00000,Dahab Lighthouse?RUSSIA">Dahab Lighthouse</option>
```
value="0000



########################################################################################################################

/opsx:propose
Check the docker container logs - there are errors.
Connect via ssh.
IP: 89.46.42.210
Port: 3084
Login: root
Password: D8yhhG6!Ddk

Container logs:
```adlanguage
docker logs --tail 100 -f windsensorbackend
```

Probably the windguru applies rate limit because the error does not happen right away - only after certain quantity of requests.
The error does not happen right away - only after certain quantity of requests.
Propose the fix.

########################################################################################################################

/opsx:propose
The wind speed data from https://www.windguru.cz/station/15693 is in knots.
I need [WindguruDataProvider.java](src/main/java/com/vb/wingfoil/provider/impl/WindguruDataProvider.java) to convert
knots to meter per second

########################################################################################################################

/opsx:propose
Create implementation of [WindDataProvider.java](src/main/java/com/vb/wingfoil/provider/WindDataProvider.java).
Call it WindguruDataProvider - code "windguru".
Analyze network for the station page https://www.windguru.cz/station/15693.
Figure out what calls must be made to gather needed information
The call to sensor would be like
```
{
        "readingWindow": 3600,
        "numberOfReadings": 5
        "sensor": {
             "label": "Assalah",
            "provider": "windguru",
            "id": "15693"
        }
}
```

Write additional tests to [WindSensorControllerMockServerTest.java](src/test/java/com/vb/wingfoil/WindSensorControllerMockServerTest.java).
You can use chrome-devtools MCP.

########################################################################################################################

/opsx:propose
The call to 
```
curl --location 'https://vm67706.vpsone.xyz/sensor-data' \
--header 'Content-Type: application/json' \
--data '{
	"sensor":
		{
			"id": "0001",
			"provider": "onechip"
		}
}'
```
Gives me
```
[
    {
        "windMax": 8.4,
        "windAvg": 6.8,
        "windMin": 4.9,
        "windDirection": 17.0,
        "timestamp": 1790017020
    },
    {
        "windMax": 7.6,
        "windAvg": 5.7,
        "windMin": 4.2,
        "windDirection": 8.0,
        "timestamp": 1790019660
    },
    {
        "windMax": 7.1,
        "windAvg": 5.5,
        "windMin": 3.5,
        "windDirection": 360.0,
        "timestamp": 1790022120
    },
    {
        "windMax": 7.1,
        "windAvg": 5.4,
        "windMin": 4.2,
        "windDirection": 11.0,
        "timestamp": 1789938600
    },
    {
        "windMax": 5.6,
        "windAvg": 4.6,
        "windMin": 3.7,
        "windDirection": 3.0,
        "timestamp": 1789941240
    }
]
```
And  "timestamp": 1790022120 is in future that is wrong.
Fix the logic of com.vb.wingfoil.provider.impl.OneChipDataProvider

########################################################################################################################

/opsx:propose
Read D:\development\Projects\GIT_MY\WindSensor\openspec\changes\optimize-map-tile-loading\tasks.md
Here we need to implement Backend functionality
## 1. Backend — Composite Tile Service
## 2. Backend — Composite Endpoint Controller
## 3. Backend — Deploy & Verify
The task is to make optimization on backend side according to D:\development\Projects\GIT_MY\WindSensor\openspec\changes\optimize-map-tile-loading\proposal.md

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


