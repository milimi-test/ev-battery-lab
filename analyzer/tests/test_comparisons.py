import copy, json, sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from battery_analysis import estimate, health_analysis, references, voltage_analysis
from comparisons import match_wind, grade_index, environment_report, overlap_energy
from analyze import analyze_group, clean_rows, load_exports
from test_analysis import T, row, archive

def segment(i,head=True):
    return dict(t=T+i*40000+(0 if head else 86400000),trip=('head' if head else 'tail')+str(i//10),
        speed_low=60,speed_kmh=65,grade=.1,latitude=44+i*.01,longitude=20,heading=0,
        outside_c=10,battery_c=20,soc=60,rain_class='dry',wind_eligible=True,
        headwind_kmh=15 if head else -15,km=.6,kwh_per_100km=16 if head else 12)

class ComparisonTests(unittest.TestCase):
    def test_window_boundary_integrates_linear_power_not_whole_interval_average(self):
        x=dict(t=0,end=10000,ms=10000,power0=0,power1=20,net=10/360)
        self.assertAlmostEqual(overlap_energy(x,5000,10000),15*5/3600)
        self.assertAlmostEqual(overlap_energy(x,0,5000)+overlap_energy(x,5000,10000),x['net'])

    def test_instant_consumption_needs_fresh_speed_above_threshold(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T,power=12),row(T+10000,power=-6),row(T+20000)]
            rs[2]['vehicle_speed_kmh']=0
            archive(p,rs);clean=clean_rows(load_exports([p])['test'])
            self.assertEqual(clean[0]['instant_consumption_kwh_100km'],20)
            self.assertEqual(clean[1]['instant_consumption_kwh_100km'],-10)
            self.assertIsNone(clean[2]['instant_consumption_kwh_100km'])

    def test_reference_values_and_no_extrapolation(self):
        models={r['id']:r for r in references()}
        self.assertAlmostEqual(estimate(3.94,models['nmc622-lp57']['points']),80)
        self.assertAlmostEqual(estimate(4.052,models['calb-nmc532']['points']),79.4048716,5)
        self.assertIsNone(estimate(3.0,models['nmc622-lp57']['points']))
        self.assertIsNone(estimate(float('nan'),models['lg-m50']['points']))

    def test_all_soc_snapshots_retained_but_cached_voltage_not_recounted(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T+i*10000,soc=60-i) for i in range(3)]
            rs[1]['bms_extra_at_ms']=T
            rs[2]['bms_extra_at_ms']=T-200000
            archive(p,rs);g=load_exports([p])['test'];out=voltage_analysis(clean_rows(g),g['bms'])
            self.assertEqual(len(out['soc_readings']),3)
            self.assertEqual(len(out['soc_pairs']),1)
            self.assertEqual(out['soc_pairs'][0]['soc_percent'],60)
            self.assertEqual(out['soc_readings'][2]['mean_voltage_v'],3.7)
            self.assertFalse(out['soc_readings'][2]['unique_paired'])

    def test_delayed_voltage_aligns_to_earlier_soc_and_current(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T,soc=60),row(T+10000,soc=59)]
            rs[0].pop('cell_voltage_average_v');rs[1]['bms_extra_at_ms']=T
            archive(p,rs);g=load_exports([p])['test'];out=voltage_analysis(clean_rows(g),g['bms'])
            self.assertEqual(out['soc_pairs'][0]['soc_percent'],60)

    def test_matrix_grade_boundaries(self):
        self.assertEqual([grade_index(x) for x in [-4,-3,-1,0,1,3,4]],[0,1,2,2,2,3,4])

    def test_speed_bins_are_ten_kmh(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T+i*10000) for i in range(3)]
            for r in rs:r['vehicle_speed_kmh']=65
            archive(p,rs);g=analyze_group(load_exports([p])['test'])
            self.assertEqual(g['speed'][0]['group'],'60–<70 km/h')

    def test_matched_wind_has_minimum_trips_distance_and_unique_pairs(self):
        rows=[segment(i,h) for i in range(20) for h in [True,False]]
        bins,pairs=match_wind(rows)
        self.assertEqual(len(pairs),20);self.assertAlmostEqual(bins[0]['paired_delta'],4)
        self.assertEqual(len({p['tail_t'] for p in pairs}),20)
        short,_=match_wind(rows[:10]);self.assertIsNone(short[0]['paired_delta'])
        # One trip on either side cannot masquerade as independent evidence.
        for r in rows:r['trip']='h' if r['headwind_kmh']>0 else 't'
        one,_=match_wind(rows);self.assertIsNone(one[0]['paired_delta'])

    def test_wind_cannot_compare_different_speed_slope_temperature_or_road(self):
        for change in [dict(speed_low=70,speed_kmh=75),dict(grade=.5),dict(outside_c=20),
                       dict(battery_c=30),dict(soc=80),dict(rain_class='wet'),dict(heading=180),dict(latitude=45)]:
            h=segment(0);t=segment(0,False);t.update(change)
            _,pairs=match_wind([h,t]);self.assertEqual(pairs,[],change)

    def test_matched_wind_does_not_force_expected_sign(self):
        rows=[segment(i,h) for i in range(20) for h in [True,False]]
        for r in rows:r['kwh_per_100km']=10 if r['headwind_kmh']>0 else 14
        bins,_=match_wind(rows);self.assertEqual(bins[0]['paired_delta'],-4)

    def test_weather_direction_is_from_not_to(self):
        gps=[dict(t=T+i*5000,latitude=44+i*.0008,longitude=20,altitude_m=100,accuracy_m=5) for i in range(6)]
        iv=[dict(t=T+i*5000,end=T+(i+1)*5000,ms=5000,net=.01,speed=64) for i in range(5)]
        w=dict(fetched_at_ms=T,latitude=44,longitude=20,wind_speed_10m_kmh=20,wind_from_degrees=180)
        out=environment_report(gps,iv,[w]);self.assertAlmostEqual(out['segments'][0]['headwind_kmh'],-20)
        w['fetched_at_ms']=T-3600001
        self.assertIsNone(environment_report(gps,iv,[w])['segments'][0]['headwind_kmh'])

    def test_recorded_soh_decline_is_not_copied_into_cells(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T),row(T+86400000)];rs[1]['soh_percent']=89
            archive(p,rs);g=load_exports([p])['test'];out=health_analysis(clean_rows(g),2)
            self.assertEqual(out['health_pack']['change_pp'],-1)
            self.assertIsNone(out['health_groups'][0]['bms_soh_change_pp'])

    def test_group_soh_change_requires_actual_group_measurements(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T),row(T+86400000)]
            for r,values in zip(rs,[[90,91],[89,91]]):r.update(cell_soh_at_ms=r['timestamp_ms'],cell_soh_percent=values)
            archive(p,rs);g=load_exports([p])['test'];out=health_analysis(clean_rows(g),2)
            self.assertEqual(out['health_groups'][0]['bms_soh_change_pp'],-1)
            self.assertEqual(out['health_groups'][1]['bms_soh_change_pp'],0)

if __name__=='__main__': unittest.main()
