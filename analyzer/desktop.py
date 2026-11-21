"""Windows desktop entry point. Data stays on this computer."""
import sys, threading, webbrowser
from pathlib import Path
from datetime import datetime
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from analyze import run, VERSION

def main():
    root=tk.Tk(); root.title('Battery Lab Analyzer '+VERSION); root.geometry('850x620'); root.minsize(680,500)
    root.configure(bg='#08131f'); style=ttk.Style(); style.theme_use('clam')
    style.configure('TFrame',background='#08131f'); style.configure('TLabel',background='#08131f',foreground='#eaf6ff',font=('Segoe UI',11))
    style.configure('Title.TLabel',font=('Segoe UI',26,'bold'),foreground='#40dcf0')
    style.configure('TButton',font=('Segoe UI',11),padding=12,background='#17394d',foreground='white')
    frame=ttk.Frame(root,padding=26); frame.pack(fill='both',expand=True)
    ttk.Label(frame,text='Battery Lab Analyzer',style='Title.TLabel').pack(anchor='w')
    ttk.Label(frame,text='Exported measurements → interactive report\nIzvezena merenja → interaktivni izveštaj').pack(anchor='w',pady=(8,16))
    files=[]; listing=tk.Listbox(frame,bg='#102336',fg='#eaf6ff',selectbackground='#22536d',font=('Segoe UI',10),height=10,selectmode='extended');listing.pack(fill='both',expand=True)
    buttons=ttk.Frame(frame);buttons.pack(fill='x',pady=12)
    def add():
        for name in filedialog.askopenfilenames(title='Select measurement ZIPs / Izaberi merenja',filetypes=[('Battery Lab exports','*.zip')]):
            if name not in files: files.append(name);listing.insert('end',name)
    def remove():
        for index in reversed(listing.curselection()): files.pop(index);listing.delete(index)
    ttk.Button(buttons,text='Add ZIPs / Dodaj ZIP',command=add).pack(side='left',padx=(0,8))
    ttk.Button(buttons,text='Remove / Ukloni',command=remove).pack(side='left')
    destination=tk.StringVar(value=str(Path.home()/'Documents'/'Battery Lab Reports'))
    ttk.Label(frame,text='Report folder / Folder izveštaja').pack(anchor='w')
    folder=ttk.Frame(frame);folder.pack(fill='x',pady=8);ttk.Entry(folder,textvariable=destination).pack(side='left',fill='x',expand=True)
    def choose():
        selected=filedialog.askdirectory()
        if selected: destination.set(selected)
    ttk.Button(folder,text='Browse / Izaberi',command=choose).pack(side='right',padx=(8,0))
    status=tk.StringVar(value='Offline · Original ZIPs remain unchanged / Originali ostaju neizmenjeni')
    ttk.Label(frame,textvariable=status,wraplength=740).pack(anchor='w',pady=10)
    def start():
        if not files: messagebox.showinfo('Select exports','Add at least one ZIP / Dodaj bar jedan ZIP.');return
        selected=list(files);target=Path(destination.get())/datetime.now().strftime('report-%Y%m%d-%H%M%S-%f')
        action.configure(state='disabled');status.set('Analysing… / Analiza je u toku…')
        def worker():
            try:
                report=run(selected,target)
                root.after(0,lambda:done(report,None))
            except Exception as error: root.after(0,lambda msg=str(error):done(None,msg))
        threading.Thread(target=worker,daemon=True).start()
    def done(report,error):
        action.configure(state='normal')
        if error: status.set('Analysis failed / Analiza nije završena');messagebox.showerror('Battery Lab Analyzer',error)
        else: status.set('Saved / Sačuvano: '+str(report));webbrowser.open(report.resolve().as_uri())
    action=ttk.Button(frame,text='Create report / Napravi izveštaj',command=start);action.pack(fill='x',pady=8)
    for arg in sys.argv[1:]:
        if arg.lower().endswith('.zip'):files.append(arg);listing.insert('end',arg)
    root.mainloop()

if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='--headless':
        del sys.argv[1]
        from analyze import main as analyze_main
        # A windowed EXE has no console streams.
        if sys.stdout is None:
            import os
            sys.stdout=open(os.devnull,'w')
        analyze_main()
    else: main()
