Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead('docs/NeoBank_PRD_Final.docx')
$entry = $zip.GetEntry('word/document.xml')
$stream = $entry.Open()
$reader = New-Object System.IO.StreamReader($stream)
$xml = [xml]$reader.ReadToEnd()
$stream.Close()
$zip.Dispose()

$paragraphs = @()
foreach ($p in $xml.GetElementsByTagName('w:p')) {
    $textParts = @()
    foreach ($t in $p.GetElementsByTagName('w:t')) {
        $textParts += $t.InnerText
    }
    if ($textParts.Count -gt 0) {
        $paragraphs += ($textParts -join '')
    }
}
$fullText = $paragraphs -join "`r`n"
Set-Content -Path 'scratch/prd_text.txt' -Value $fullText -Encoding UTF8
Write-Output "Successfully extracted PRD text paragraphs: $($paragraphs.Count), total chars: $($fullText.Length)"
