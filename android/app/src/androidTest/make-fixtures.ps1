# Regenerates the MP4 fixtures in assets/ used by VideoResultWriterTest.
# Needs ffmpeg with libx264, libx265, libvpx-vp9 and libaom-av1. Usage: pwsh -File make-fixtures.ps1 [ffmpeg.exe]
param([string]$Ffmpeg = 'C:\ProgramData\chocolatey\bin\ffmpeg.exe')
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'assets'
New-Item -ItemType Directory -Force $out | Out-Null

$src = @('-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=24:duration=2',
         '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=2')
$aac = @('-c:a', 'aac', '-ac', '2', '-b:a', '96k', '-shortest', '-movflags', '+faststart')

function Make([string]$name, [string[]]$video) {
    & $Ffmpeg -y -hide_banner -loglevel error @src @video @aac (Join-Path $out $name)
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $name" }
}

Make 'h264.mp4'         @('-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-bf', '0')
Make 'h264_bframes.mp4' @('-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-bf', '3', '-x264-params', 'b-pyramid=normal')
Make 'hevc.mp4'         @('-c:v', 'libx265', '-pix_fmt', 'yuv420p', '-tag:v', 'hvc1', '-x265-params', 'log-level=error')
Make 'vp9.mp4'          @('-c:v', 'libvpx-vp9', '-pix_fmt', 'yuv420p', '-b:v', '300k')
Make 'av1.mp4'          @('-c:v', 'libaom-av1', '-pix_fmt', 'yuv420p', '-cpu-used', '8', '-b:v', '200k')

& $Ffmpeg -y -hide_banner -loglevel error -display_rotation:v:0 90 -i (Join-Path $out 'h264.mp4') -c copy (Join-Path $out 'h264_rot90.mp4')
if ($LASTEXITCODE -ne 0) { throw 'ffmpeg failed for h264_rot90.mp4' }

Get-ChildItem $out -Filter *.mp4 | Select-Object Name, Length
