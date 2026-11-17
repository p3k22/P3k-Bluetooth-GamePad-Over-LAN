using System.IO;
using System.Text;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>Splits a byte stream into newline-delimited UTF-8 lines, however the bytes are fragmented.</summary>
public sealed class LineFramer
{
    public const int MaxLineBytes = 16384;

    private const byte LineFeed = (byte)'\n';

    private static readonly Encoding Utf8 = new UTF8Encoding(false, true);

    private readonly MemoryStream _line = new();

    /// <summary>Adds received bytes and returns every line they completed. Empty lines are skipped.</summary>
    public IReadOnlyList<string> Add(ReadOnlySpan<byte> bytes)
    {
        var completedLines = new List<string>();
        foreach (var value in bytes)
        {
            if (value == LineFeed)
            {
                CompleteLine(completedLines);
            }
            else
            {
                AppendByte(value);
            }
        }

        return completedLines;
    }

    private void CompleteLine(List<string> completedLines)
    {
        if (_line.Length > 0)
        {
            var text = Utf8.GetString(_line.GetBuffer(), 0, (int)_line.Length);
            completedLines.Add(text.TrimEnd('\r'));
        }

        _line.SetLength(0);
        _line.Position = 0;
    }

    private void AppendByte(byte value)
    {
        if (_line.Length >= MaxLineBytes)
        {
            throw new InvalidDataException("Input message exceeds 16 KB.");
        }

        _line.WriteByte(value);
    }
}
