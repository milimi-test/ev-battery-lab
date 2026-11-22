param([string]$Python = 'python', [string]$DistPath = 'analyzer/dist')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & $Python -m PyInstaller --noconfirm --onefile --windowed --name Battery-Lab-Analyzer-0.2-Windows `
        --add-data 'analyzer/report.html;.' --add-data 'analyzer/report.css;.' `
        --add-data 'analyzer/report.js;.' --add-data 'analyzer/references.json;.' --add-data 'analyzer/aging_research.json;.' `
        --distpath $DistPath --workpath analyzer/build analyzer/desktop.py
    if ($LASTEXITCODE -ne 0) { throw 'Analyzer build failed.' }
} finally { Pop-Location }
