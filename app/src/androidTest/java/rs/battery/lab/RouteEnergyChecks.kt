package rs.battery.lab

import android.content.Context
import kotlin.math.abs

/** Regression checks independent of internet, car, GPS permissions, or stored trips. */
internal fun checkRouteEnergy(context: Context) {
    val peak = checkNotNull(RouteTerrain.height(context, 43.2691, 20.823))
    val plain = checkNotNull(RouteTerrain.height(context, 45.25, 19.84))
    check(peak in 1870.0..2060.0 && plain in 70.0..110.0)
    check(RouteTerrain.height(context, 55.0, 10.0) == null)

    val route = listOf(RouteVertex(44.0, 20.0, 2), RouteVertex(44.05, 20.0, 2), RouteVertex(44.1, 20.0, 2))
    val hill = terrainProfile(route) { lat, _ -> 100 - 2000 * abs(lat - 44.05) }
    check(hill.coverage > .99 && hill.ascent in 92.0..100.1 && hill.descent in 92.0..100.1)
    val noElevation = terrainProfile(route) { _, _ -> null }
    check(noElevation.coverage == 0.0)

    val base = routeEnergyKwh(100.0, 16.0, RouteProfile(0.0, 0.0, 1.0), 0.0)
    check(abs(base - 16.0) < 1e-8)
    val hillEnergy = routeEnergyKwh(100.0, 16.0, RouteProfile(500.0, 0.0, 1.0), 0.0)
    check(hillEnergy in 18.49..18.51)
    val head = routeWindAdjustmentKwh(100.0, 90, 20.0, 0.0)
    val tail = routeWindAdjustmentKwh(100.0, 90, -20.0, 0.0)
    check(head in 3.78..3.81 && tail in -3.05..-3.01)
    check(routeWindAdjustmentKwh(100.0, 90, 0.0, 0.0) == 0.0)

    val now = 1_000_000_000L
    val source = WeatherSample(now,44.0,20.0,null,null,null,null,null,20.0,180.0)
    check(RouteWind.at(emptyList(),source,44.0,20.0,now,now)?.speed == 20.0)
    check(RouteWind.at(emptyList(),source,46.0,20.0,now,now) == null)
    check(RouteWind.at(emptyList(),source,44.0,20.0,now + 7_200_000,now + 7_200_000) == null)
}
