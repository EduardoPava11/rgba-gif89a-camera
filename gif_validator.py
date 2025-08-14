#!/usr/bin/env python3
"""
GIF89a Validation Utility
Parses a GIF file and reports header, frame count, delays, palette sizes, and loop flag
"""

import sys
import struct

def parse_gif89a(filepath):
    """Parse GIF89a file and extract key metadata"""
    try:
        with open(filepath, 'rb') as f:
            data = f.read()
    except FileNotFoundError:
        return {"error": f"File not found: {filepath}"}
    except Exception as e:
        return {"error": f"Could not read file: {e}"}
    
    if len(data) < 13:
        return {"error": "File too small to be a valid GIF"}
    
    # Parse header
    signature = data[0:3].decode('ascii', errors='ignore')
    version = data[3:6].decode('ascii', errors='ignore')
    
    if signature != 'GIF':
        return {"error": f"Invalid signature: {signature} (expected GIF)"}
    
    header = f"{signature}{version}"
    
    # Parse Logical Screen Descriptor
    width = struct.unpack('<H', data[6:8])[0]
    height = struct.unpack('<H', data[8:10])[0]
    packed = data[10]
    global_color_table_flag = bool(packed & 0x80)
    global_color_table_size = 2 << (packed & 0x07) if global_color_table_flag else 0
    
    pos = 13  # Skip LSD
    if global_color_table_flag:
        pos += global_color_table_size * 3  # Skip GCT
    
    frames = []
    loop_count = None
    loop_present = False
    
    # Parse data stream
    while pos < len(data):
        if data[pos] == 0x21:  # Extension
            ext_type = data[pos + 1]
            pos += 2
            
            if ext_type == 0xFF:  # Application Extension
                block_size = data[pos]
                pos += 1
                if block_size >= 11:
                    app_id = data[pos:pos+11].decode('ascii', errors='ignore')
                    pos += 11
                    if app_id == 'NETSCAPE2.0':
                        loop_present = True
                        # Parse loop count
                        while pos < len(data) and data[pos] != 0:
                            sub_block_size = data[pos]
                            pos += 1
                            if sub_block_size >= 3 and data[pos] == 1:  # Loop extension
                                loop_count = struct.unpack('<H', data[pos+1:pos+3])[0]
                            pos += sub_block_size
                        pos += 1  # Skip terminator
                    else:
                        # Skip unknown application extension
                        while pos < len(data) and data[pos] != 0:
                            pos += data[pos] + 1
                        pos += 1
                else:
                    pos += block_size
            
            elif ext_type == 0xF9:  # Graphics Control Extension
                block_size = data[pos]
                pos += 1
                if block_size >= 4:
                    disposal = (data[pos] >> 2) & 0x07
                    delay = struct.unpack('<H', data[pos+1:pos+3])[0]  # in centiseconds
                    transparent_index = data[pos+3]
                    transparent_flag = bool(data[pos] & 0x01)
                    pos += 4
                else:
                    pos += block_size
                pos += 1  # Skip terminator
            
            else:
                # Skip other extensions
                while pos < len(data) and data[pos] != 0:
                    pos += data[pos] + 1
                pos += 1
        
        elif data[pos] == 0x2C:  # Image Descriptor
            pos += 1  # Skip separator
            left = struct.unpack('<H', data[pos:pos+2])[0]
            top = struct.unpack('<H', data[pos+2:pos+4])[0]
            img_width = struct.unpack('<H', data[pos+4:pos+6])[0]
            img_height = struct.unpack('<H', data[pos+6:pos+8])[0]
            packed = data[pos+8]
            pos += 9
            
            local_color_table_flag = bool(packed & 0x80)
            local_color_table_size = 2 << (packed & 0x07) if local_color_table_flag else 0
            
            palette_size = local_color_table_size if local_color_table_flag else global_color_table_size
            
            frames.append({
                'width': img_width,
                'height': img_height,
                'delay_cs': delay if 'delay' in locals() else 0,
                'palette_size': palette_size,
                'disposal': disposal if 'disposal' in locals() else 0,
                'transparent': transparent_flag if 'transparent_flag' in locals() else False
            })
            
            # Skip Local Color Table
            if local_color_table_flag:
                pos += local_color_table_size * 3
            
            # Skip LZW minimum code size
            pos += 1
            
            # Skip image data sub-blocks
            while pos < len(data) and data[pos] != 0:
                pos += data[pos] + 1
            pos += 1  # Skip terminator
        
        elif data[pos] == 0x3B:  # Trailer
            break
        
        else:
            pos += 1  # Skip unknown bytes
    
    return {
        "header": header,
        "width": width,
        "height": height,
        "frame_count": len(frames),
        "frames": frames,
        "loop_present": loop_present,
        "loop_count": loop_count,
        "infinite_loop": loop_count == 0 if loop_present else False,
        "global_color_table": global_color_table_size if global_color_table_flag else None,
        "file_size": len(data)
    }

def format_report(result):
    """Format the parsing result into a readable report"""
    if "error" in result:
        return f"❌ Error: {result['error']}"
    
    report = []
    report.append(f"🎞️  GIF Validation Report")
    report.append(f"=" * 50)
    report.append(f"📄 File Size:        {result['file_size']:,} bytes")
    report.append(f"📋 Header:           {result['header']}")
    report.append(f"📐 Dimensions:       {result['width']}×{result['height']}")
    report.append(f"🎬 Frame Count:      {result['frame_count']}")
    report.append(f"🔄 Loop Present:     {'✅ Yes' if result['loop_present'] else '❌ No'}")
    
    if result['loop_present']:
        if result['infinite_loop']:
            report.append(f"♾️  Loop Count:       0 (infinite)")
        else:
            report.append(f"🔁 Loop Count:       {result['loop_count']}")
    
    if result['global_color_table']:
        report.append(f"🎨 Global Palette:   {result['global_color_table']} colors")
    else:
        report.append(f"🎨 Global Palette:   None (using Local Color Tables)")
    
    report.append("")
    report.append("📊 Frame Details:")
    report.append("Frame | Delay (cs) | Palette | Disposal | Size")
    report.append("-" * 50)
    
    for i, frame in enumerate(result['frames'][:10]):  # Show first 10 frames
        report.append(f"{i+1:5d} | {frame['delay_cs']:10d} | {frame['palette_size']:7d} | {frame['disposal']:8d} | {frame['width']}×{frame['height']}")
    
    if len(result['frames']) > 10:
        report.append(f"... and {len(result['frames']) - 10} more frames")
    
    # Validation summary
    report.append("")
    report.append("✅ Validation Summary:")
    
    if result['header'] == 'GIF89a':
        report.append("   ✅ Valid GIF89a header")
    else:
        report.append(f"   ❌ Invalid header: {result['header']}")
    
    if result['frame_count'] == 81:
        report.append("   ✅ Correct frame count (81)")
    else:
        report.append(f"   ⚠️  Frame count: {result['frame_count']} (expected 81)")
    
    if result['loop_present'] and result['infinite_loop']:
        report.append("   ✅ Infinite loop enabled")
    elif result['loop_present']:
        report.append(f"   ⚠️  Limited loop ({result['loop_count']} times)")
    else:
        report.append("   ❌ No loop extension")
    
    # Check delays (should be 4cs for ~25fps)
    if result['frames']:
        delays = [f['delay_cs'] for f in result['frames']]
        avg_delay = sum(delays) / len(delays)
        if all(d == 4 for d in delays):
            report.append("   ✅ Consistent 4cs delay (~25 fps)")
        elif 3 <= avg_delay <= 5:
            report.append(f"   ⚠️  Average delay: {avg_delay:.1f}cs (~{100/avg_delay:.1f} fps)")
        else:
            report.append(f"   ❌ Unusual delay: {avg_delay:.1f}cs")
    
    # Check dimensions
    if result['width'] == 81 and result['height'] == 81:
        report.append("   ✅ Correct dimensions (81×81)")
    else:
        report.append(f"   ⚠️  Dimensions: {result['width']}×{result['height']} (expected 81×81)")
    
    return "\n".join(report)

def main():
    if len(sys.argv) != 2:
        print("Usage: python3 gif_validator.py <path_to_gif>")
        sys.exit(1)
    
    filepath = sys.argv[1]
    result = parse_gif89a(filepath)
    report = format_report(result)
    print(report)
    
    # Save report to file
    report_file = f"{filepath}_report.txt"
    with open(report_file, 'w') as f:
        f.write(report)
    print(f"\n📋 Report saved to: {report_file}")

if __name__ == "__main__":
    main()
