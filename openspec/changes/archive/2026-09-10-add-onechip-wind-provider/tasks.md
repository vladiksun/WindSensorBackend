## 1. Dependencies

- [x] 1.1 Add Jsoup to `build.gradle` as an inline coordinate, e.g. `implementation("org.jsoup:jsoup:1.18.1")` (confirm latest stable at implementation time)

## 2. Data model

- [x] 2.1 Create `com.vb.wingfoil.response.onechip.OneChipMeasurement` record with fields `(float min, float avg, float gust, float dir, long timestamp)` (plain record, no `@Serdeable`)

## 3. Provider implementation

- [x] 3.1 Create `com.vb.wingfoil.provider.impl.OneChipDataProvider extends BaseWindyDataProvider<OneChipMeasurement>`, annotated `@Singleton`, with `public static final String NAME = "onechip"` and a constructor injecting `@Named(NAME) WindDataProviderConfig` + `ObjectMapper` (pass both to `super`)
- [x] 3.2 Implement `getCallUrl(sensorId)` returning `getUrl().formatted(sensorId)`
- [x] 3.3 Implement `extractTimedReadings`: return a single empty reading for blank bodies; otherwise parse the `#data_table` rows with Jsoup (skip the `hh:mm` header row), map fixed cell indices (min=`[2]`, avg=`[3]`, gust=`[4]`, direction=`[7]` stripped of `°`, time=`[0]`), derive timestamps from the "Arrived" date + `hh:mm` (fallback to today), reverse to ascending order, skip malformed rows, and delegate to `buildTimedReadings`/`getReadingsByInterval`; recover to `List.of(SensorDataDTO.empty())` and log on parse failure
- [x] 3.4 Implement `mapToDTO(m)` → `new SensorDataDTO(m.gust(), m.avg(), m.min(), m.dir(), m.timestamp())` and `getLastReading(data)` → `mapToDTO(data.getLast())`

## 4. Configuration

- [x] 4.1 Add to `src/main/resources/application.yml` under `wind-sensor.wind-providers`: `onechip:` with `url: https://1chip.ru/windt.php?id=%s`

## 5. Testing

- [x] 5.1 Add a captured 1chip.ru HTML fixture (trimmed to a representative subset of rows plus the Arrived line) under `src/test/resources`
- [x] 5.2 Write `OneChipDataProviderTest` asserting: correct min/avg/max/direction per row, ascending time order, timestamps derived from the Arrived date, windowing behavior (`readingWindowSeconds=0 && numberOfReadings=0` returns only the latest reading), and empty/malformed-response handling returns a single empty reading

## 6. Verification

- [x] 6.1 Run `./gradlew -q spotlessCheck` (run `spotlessApply` first if it fails)
- [x] 6.2 Compile: `./gradlew -q :compileJava` and `./gradlew -q :compileTestJava`
- [x] 6.3 Run targeted tests: `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.OneChipDataProviderTest'`
