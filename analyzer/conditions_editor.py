"""Optional dated context editor; never changes the measurement archives."""
import json
import tkinter as tk
from tkinter import ttk, messagebox
from pathlib import Path
from long_term import load_conditions


def edit_conditions(parent,path,profiles):
    path=Path(path)
    try:data=json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else dict(periods=[],reference_kwh={})
    except Exception as e:messagebox.showerror('Conditions / Uslovi',str(e));return
    win=tk.Toplevel(parent);win.title('Tires, HVAC & cycle reference / Gume, klima i osnova ciklusa');win.geometry('920x650')
    frame=ttk.Frame(win,padding=18);frame.pack(fill='both',expand=True)
    ttk.Label(frame,text='Dated context, UTC · End is exclusive / Periodi u UTC, kraj nije uključen',wraplength=850).pack(anchor='w')
    ttk.Label(frame,text='Leave unknown when uncertain. HVAC entries must cover only periods when the state was constant.\nNepoznato ostavi unknown. Klima: unesi samo period za koji znaš da je stalno bila u istom stanju.',wraplength=850).pack(anchor='w',pady=8)
    tree=ttk.Treeview(frame,columns=('profile','start','end','tires','hvac'),show='headings',height=7)
    for k,label in [('profile','Vehicle / Vozilo'),('start','Start UTC'),('end','End UTC'),('tires','Tires / Gume'),('hvac','HVAC')]:tree.heading(k,text=label);tree.column(k,width=150)
    tree.pack(fill='both',expand=True)
    def refresh():
        tree.delete(*tree.get_children())
        for i,p in enumerate(data.get('periods',[])):tree.insert('', 'end',iid=str(i),values=(p['vehicle_profile'],p['start_utc'],p['end_utc'],p.get('tire_type','unknown'),p.get('hvac','unknown')))
    refresh();form=ttk.Frame(frame);form.pack(fill='x',pady=12)
    variables={k:tk.StringVar(value=val) for k,val in [('profile',profiles[0] if profiles else 'legacy-e208'),('start','2026-11-01'),('end','2027-04-01'),('tires','unknown'),('label',''),('hvac','unknown'),('capacity','')]}
    def field(row,key,label,choices=None):
        ttk.Label(form,text=label).grid(row=row,column=0,sticky='w',padx=3,pady=3)
        w=ttk.Combobox(form,textvariable=variables[key],values=choices,width=48,state='readonly' if key in ['tires','hvac'] else 'normal') if choices else ttk.Entry(form,textvariable=variables[key],width=51)
        w.grid(row=row,column=1,sticky='ew',padx=5,pady=3)
    field(0,'profile','Vehicle profile / Profil',profiles);field(1,'start','From UTC / Od');field(2,'end','Until UTC / Do')
    field(3,'tires','Tire type / Vrsta guma',['unknown','summer','winter','all-season']);field(4,'label','Model, size / Model, dimenzija')
    field(5,'hvac','Climate / Klima',['unknown','on','off']);field(6,'capacity','Fixed initial usable kWh (optional) / Početni kWh')
    ttk.Label(frame,text='Capacity sets one fixed reference for this vehicle across all dates. Blank keeps the profile default.\nKapacitet je fiksna početna osnova za sve datume, ne trenutni kapacitet prema SOH.',wraplength=850).pack(anchor='w')
    def persist(candidate):
        path.parent.mkdir(parents=True,exist_ok=True);temporary=path.with_suffix('.validation.json')
        temporary.write_text(json.dumps(candidate,ensure_ascii=False,indent=2),encoding='utf-8')
        try:load_conditions(temporary)
        except Exception as e:temporary.unlink(missing_ok=True);messagebox.showerror('Conditions / Uslovi',str(e),parent=win);return False
        temporary.replace(path);return True
    def save(add_period=True):
        candidate=json.loads(json.dumps(data));profile=variables['profile'].get().strip()
        if not profile:messagebox.showerror('Profile','Vehicle profile is required',parent=win);return
        cap=variables['capacity'].get().strip()
        if cap:
            try:candidate.setdefault('reference_kwh',{})[profile]=float(cap.replace(',','.'))
            except ValueError:messagebox.showerror('kWh','Enter a number / Unesi broj',parent=win);return
        if add_period:candidate.setdefault('periods',[]).append(dict(vehicle_profile=profile,start_utc=variables['start'].get().strip(),end_utc=variables['end'].get().strip(),tire_type=variables['tires'].get(),tire_label=variables['label'].get().strip(),hvac=variables['hvac'].get()))
        if persist(candidate):data.clear();data.update(candidate);refresh()
    def remove():
        chosen={int(i) for i in tree.selection()};candidate=dict(data,periods=[p for i,p in enumerate(data.get('periods',[])) if i not in chosen])
        if persist(candidate):data.clear();data.update(candidate);refresh()
    actions=ttk.Frame(frame);actions.pack(fill='x',pady=10)
    ttk.Button(actions,text='Add period / Dodaj period',command=save).pack(side='left')
    ttk.Button(actions,text='Save kWh only / Samo kWh',command=lambda:save(False)).pack(side='left',padx=6)
    ttk.Button(actions,text='Delete selected / Obriši',command=remove).pack(side='left')
    ttk.Button(actions,text='Done / Gotovo',command=win.destroy).pack(side='right')
