import copy,json,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from long_term import load_conditions,annotate_conditions,reference_capacity,match_effect,long_term_report,parse_date
from comparisons import speed_bin
from test_comparisons import segment
from test_analysis import T,row,archive
from analyze import load_exports,analyze_group


class LongTermTests(unittest.TestCase):
    def test_130_is_in_final_120_to_130_band(self):
        self.assertEqual([speed_bin(s) for s in [119.9,120,129.9,130,130.1]],[110,120,120,120,130])

    def test_conditions_reject_overlapping_periods_and_invalid_capacity(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'conditions.json';period=dict(vehicle_profile='x',start_utc='2026-01-01',end_utc='2026-02-01')
            p.write_text(json.dumps(dict(periods=[period,period])))
            with self.assertRaises(ValueError):load_conditions(p)
            p.write_text(json.dumps(dict(reference_kwh={'x':0})))
            with self.assertRaises(ValueError):load_conditions(p)

    def test_tire_history_never_backdates_and_crossing_change_is_unknown(self):
        rows=[dict(timestamp_ms=T+i*10000,hvac_on=True) for i in range(10)]
        segments=[dict(t=T,end=T+10000,outside_c=10),dict(t=T+25000,end=T+35000,outside_c=10),dict(t=T+40000,end=T+50000,outside_c=10)]
        annotate_conditions(segments,rows,[dict(changed_at_ms=T+30000,tire_profile='Winter 195/55 R16')],'x',{})
        self.assertEqual([s['tire_type'] for s in segments],['unknown','unknown','winter'])
        self.assertEqual(segments[-1]['hvac'],'on')

    def test_manual_period_scoped_to_vehicle_and_constant_hvac(self):
        ss=[dict(t=T,end=T+10000,outside_c=None)]
        config={'periods':[dict(vehicle_profile='x',start=T,end=T+10001,tire_type='summer',hvac='on')]}
        annotate_conditions(ss,[],[],'y',config);self.assertEqual(ss[0]['tire_type'],'unknown')
        annotate_conditions(ss,[],[],'x',config);self.assertEqual(ss[0]['tire_type'],'summer');self.assertEqual(ss[0]['hvac_source'],'manual dated period')

    def test_condition_matching_requires_known_other_conditions_and_same_speed(self):
        ss=[]
        for i in range(20):
            for treated in [True,False]:
                s=segment(i,treated);s.update(tire_type='winter' if treated else 'summer',tire_label='A' if treated else 'B',hvac='off',headwind_kmh=2)
                ss.append(s)
        results,pairs=match_effect(ss,'tires');self.assertEqual(len(pairs),20);self.assertEqual(results[0]['delta_kwh_100km'],4)
        for s in ss:s['hvac']='unknown'
        results,pairs=match_effect(ss,'tires');self.assertFalse(pairs);self.assertIsNone(results[0]['delta_kwh_100km'])

    def test_energy_efc_fixed_reference_and_month_boundary(self):
        t=parse_date('2026-10-31T23:59:50Z');iv=[dict(t=t,end=t+20000,ms=20000,power0=18,power1=18,gross=.1,regen=0)]
        rs=[dict(timestamp_ms=t,soh_percent=90,outside_temp_c=5),dict(timestamp_ms=t+20000,soh_percent=80,outside_temp_c=5)]
        result=long_term_report('x',rs,iv,[],[],[],{'reference_kwh':{'x':50}})
        self.assertEqual(len(result['monthly']),2)
        self.assertAlmostEqual(result['cycle_summary']['recorded_energy_efc'],.002)
        self.assertAlmostEqual(result['monthly'][0]['discharge_kwh'],.05)
        self.assertAlmostEqual(result['monthly'][1]['energy_equivalent_cycles'],.001)

    def test_counter_reset_not_spliced_into_cycle_count(self):
        rs=[dict(timestamp_ms=T+i*10000,lifetime_counters_at_ms=T+i*10000,lifetime_energy_out_kwh=v) for i,v in enumerate([100,100.1,0,.1])]
        r=long_term_report('x',rs,[],[],[],[],{'reference_kwh':{'x':50}})
        self.assertEqual(r['cycle_summary']['counter_resets'],1)
        self.assertIsNone(r['cycle_summary']['counter_discharge_kwh'])
        self.assertIsNone(r['cycle_summary']['counter_energy_efc'])

    def test_15_percent_does_not_create_lower_endpoint(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.zip';archive(p,[row(T+i*10000,soc=15.5,power=0) for i in range(4)])
            out=analyze_group(load_exports([p])['test'])
            self.assertFalse(out['soc_endpoints'])
            self.assertIsNone(out['cycle_summary']['reference_kwh'])

    def test_profile_reference_is_fixed_and_not_inferred_for_unknown_vehicle(self):
        self.assertEqual(reference_capacity('legacy-e208',{})[0],46.3)
        self.assertEqual(reference_capacity('renault_zoe_r135_52',{})[0],52)
        self.assertIsNone(reference_capacity('unidentified',{})[0])


if __name__=='__main__':unittest.main()
