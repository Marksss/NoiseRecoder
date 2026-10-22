param([Parameter(Mandatory=$true)][string]$DocumentPath,[Parameter(Mandatory=$true)][string]$OutputDirectory)
# Optional native Windows/desktop Word QA. Opens only the provided document read-only.
$ErrorActionPreference='Stop'
$source=(Resolve-Path -LiteralPath $DocumentPath).Path
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$target=(Resolve-Path -LiteralPath $OutputDirectory).Path
$word=$null
$doc=$null
try {
    $word=New-Object -ComObject Word.Application
    $word.Visible=$false
    $word.DisplayAlerts=0
    $word.AutomationSecurity=3
    $doc=$word.Documents.Open($source,$false,$true,$false)
    $doc.Repaginate()
    $pages=$doc.ComputeStatistics(2)
    $objects=@()
    foreach($shape in $doc.InlineShapes) { if($shape.Type -eq 1) { $objects += $shape.OLEFormat.ProgID } }
    $doc.ExportAsFixedFormat((Join-Path $target 'report.pdf'),17)
    [PSCustomObject]@{Pages=$pages;EmbeddedAudioObjects=$objects.Count;ObjectTypes=$objects -join ',';OpenedReadOnly=$doc.ReadOnly} | Format-List
} finally {
    if($doc) { $doc.Close(0); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($doc) }
    if($word) { $word.Quit(0); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
}
