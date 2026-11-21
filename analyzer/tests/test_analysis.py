import json, math, sys, tempfile, unittest, zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze import analyze_group, clean_rows, intervals, load_exports, positive_integral, run, environment

T=1800000000000
def row(t,power=10,soc=60):
    return dict(timestamp_ms=t,battery_power_kw=power,soc_percent=soc,soh_percent=90,
        vehicle_speed_kmh=60,vehicle_speed_at_ms=t,battery_voltage_v=360,battery_current_a=power*1000/360,
        cell_voltage_average_v=3.7,cell_voltage_min_v=3.69,cell_voltage_max_v=3.71,bms_extra_at_ms=t,
        cell_voltages_v=[3.69,3.71],cell_voltages_at_ms=t)

def archive(path,rows,extra=None,profile='test'):
    with zipfile.ZipFile(path,'w') as z:
        z.writestr('manifest.json',json.dumps(dict(vehicle_profile=profile,vehicle_name='Test vehicle',series_groups=2)))
        z.writestr('bms-readings.jsonl','\n'.join(json.dumps(r) for r in rows))
        for name,value in (extra or {}).items():z.writestr(name,value)

class AnalysisTests(unittest.TestCase):
    def test_positive_negative_and_crossing_energy(self):
        self.assertAlmostEqual(positive_integral(10,10,1),10)
        self.assertEqual(positive_integral(-10,-10,1),0)
        self.assertAlmostEqual(positive_integral(-10,10,1),2.5)
        self.assertAlmostEqual(positive_integral(10,-10,1),2.5)

    def test_dedup_profiles_and_cells(self):
        with tempfile.TemporaryDirectory() as folder:
            paths=[Path(folder)/f'{i}.zip' for i in range(3)]
            rows=[row(T),row(T+10000)]
            rows[1]['cell_voltages_at_ms']=T
            archive(paths[0],rows);archive(paths[1],rows);archive(paths[2],rows,profile='zoe')
            groups=load_exports(paths);self.assertEqual(len(groups),2)
            report=analyze_group(groups['test']);self.assertEqual(report['readings'],2)
            self.assertEqual(report['duplicates'],2);self.assertEqual(report['cells'][0]['readings'],1)
            self.assertAlmostEqual(report['cells'][0]['median_deviation_mv'],-10)

    def test_gaps_and_missing_power_are_not_zero(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T),row(T+10000),row(T+100000)]
            archive(p,rs);iv=intervals(clean_rows(load_exports([p])['test']))
            self.assertEqual(len(iv),1);self.assertAlmostEqual(iv[0]['net'],10/360)

    def test_capacity_requires_coverage(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';rs=[row(T+i*10000,soc=60-i/3.6) for i in range(37)]
            trip=dict(id='one',start=T,end=T+360000,odo0=100,odo1=106,soc0=60,soc1=50)
            archive(p,rs,{'trip-sessions.jsonl':json.dumps(trip)})
            trip_result=analyze_group(load_exports([p])['test'])['trips'][0]
            self.assertAlmostEqual(trip_result['energy_kwh'],1)
            self.assertAlmostEqual(trip_result['capacity_estimate_kwh'],10)
            self.assertAlmostEqual(trip_result['kwh_per_100km'],100/6)
            archive(p,rs[:5]+rs[20:],{'trip-sessions.jsonl':json.dumps(trip)})
            incomplete=analyze_group(load_exports([p])['test'])['trips'][0]
            self.assertIsNone(incomplete['capacity_estimate_kwh']);self.assertIsNone(incomplete['kwh_per_100km'])

    def test_stale_speed_and_voltage_are_excluded(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';r=row(T);r['vehicle_speed_at_ms']=T-20000;r['bms_extra_at_ms']=T-150000
            archive(p,[r]);clean=clean_rows(load_exports([p])['test'])[0]
            self.assertIsNone(clean['vehicle_speed_kmh']);self.assertIsNone(clean['cell_voltage_average_v'])

    def test_html_is_offline_and_escapes_export_text(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';archive(p,[row(T),row(T+10000)],profile='</script><script>alert(1)</script>')
            output=run([p],Path(folder)/'report');text=output.read_text(encoding='utf-8')
            self.assertNotIn('</script><script>alert(1)',text)
            self.assertNotIn('<script src=',text);self.assertNotIn('__REPORT_DATA__',text)

    def test_flat_and_headwind_segments(self):
        gps=[dict(t=T+i*10000,latitude=44+i*.001,longitude=20,altitude_m=100,accuracy_m=5) for i in range(5)]
        iv=[dict(t=T+i*10000,end=T+(i+1)*10000,ms=10000,net=.05,speed=40) for i in range(4)]
        weather=[dict(fetched_at_ms=T,latitude=44,longitude=20,wind_speed_10m_kmh=20,wind_from_degrees=0)]
        terrain,wind,route=environment(gps,iv,weather)
        self.assertIn('Flat',terrain[0]['group']);self.assertIn('Headwind',wind[0]['group'])
        self.assertAlmostEqual(terrain[0]['net_kwh'],.2)

    def test_charging_loss_only_complete_linked_session(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'a.zip';c=dict(id='c',started_at_ms=T,ended_at_ms=T+3600000,energy_basis='negative_power_integral',battery_energy_added_kwh=9,power_coverage_percent=99)
            m=dict(id='m',charge_id='c',charger_delivered_kwh=10,recorded_at_ms=T+3600000)
            extra={'charge-sessions.jsonl':json.dumps(c),'charger-meter.jsonl':json.dumps(m)}
            archive(p,[row(T)],extra);self.assertAlmostEqual(analyze_group(load_exports([p])['test'])['charges'][0]['loss_percent'],10)
            c['power_coverage_percent']=40;extra['charge-sessions.jsonl']=json.dumps(c)
            archive(p,[row(T)],extra);self.assertIsNone(analyze_group(load_exports([p])['test'])['charges'][0]['loss_percent'])

if __name__=='__main__':unittest.main()
