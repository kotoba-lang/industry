# ECU Challenge Solution

## Challenge Analysis

This is a multi-stage automotive ECU penetration testing challenge that simulates real-world vehicle network attacks.

### Key Findings

1. **Entry Point**: Infotainment system (`infotainment_service`) with admin authentication
2. **Authentication Algorithms**: Two security levels with seed-based key generation
3. **Network Setup**: WiFi AP access required for challenge interaction
4. **Target**: Navigate through ECUs to reach engine control module

### Binary Analysis Results

#### Infotainment Service (`extracted/bin/infotainment_service`)
- **Type**: ELF 64-bit ARM aarch64 executable
- **Key Strings**:
  - "Enter admin authentication code:"
  - "Full admin privileges unlocked!"
  - "Hardware ID: ECU-INFO-001"
  - "CAN Bus: Active"

#### HSCand Daemon (`extracted/bin/hscand`)
- **Type**: ELF 32-bit ARM shared object  
- **Purpose**: Handles inter-ECU communication via sockets
- **Communication**: "micom => hscanDaemon", "hscand => framework"

### Authentication Algorithm

Based on the hint provided, there are two authentication levels:

#### Level 1 (0x01)
```python
key = ((seed ^ 0xA5A5A5A5) + (timestamp & 0xFF)) & 0xFFFFFFFF
```

#### Level 3 (0x03)
```python
step1 = seed ^ 0x5A5A5A5A
step2 = ((step1 << 3) | (step1 >> 29)) & 0xFFFFFFFF
step3 = step2 + ((timestamp & 0xFFFF) * 0x9E3779B9)
key = step3 & 0xFFFFFFFF
```

### Challenge Workflow

1. **Connect to WiFi**:
   - SSID: `CarHackingCTF`
   - Password: `Route44Polyglot33$`

2. **Access Challenge Server**:
   - URL: `http://192.168.22.193:8080/`

3. **Extract Challenge Parameters**:
   - The server will provide a seed value
   - Seeds expire in 10 seconds (timing critical!)

4. **Calculate Authentication Key**:
   - Use the appropriate algorithm based on security level
   - Submit calculated key to gain access

5. **Navigate ECU Network**:
   - Progress through multiple ECUs
   - Reach the engine control module for flag

## Tools Provided

### 1. Quick Authentication Calculator (`quick_auth.py`)
```bash
python3 quick_auth.py 0x12345678 1        # Level 1
python3 quick_auth.py 0xDEADBEEF 3        # Level 3
```

### 2. Full Exploit Framework (`ecu_exploit.py`)
- Automated connection to challenge server
- Parameter extraction from server responses
- Authentication key calculation and submission
- Service discovery and exploitation

### 3. Analysis Tools (`ecu_analysis.py`)
- Binary analysis and string extraction
- Constant pattern detection
- Authentication algorithm testing

## Example Usage

### Manual Key Calculation
```bash
# For a seed 0x12345678 at current time, level 1:
python3 quick_auth.py 0x12345678 1

# Output:
# Seed: 0x12345678
# Level: 0x01
# Timestamp: 1754766788
# Key: 0xb791f4a1
```

### Automated Exploitation
```bash
# Run full exploit (requires WiFi connection)
python3 ecu_exploit.py
```

## Important Notes

1. **Timing is Critical**: Seeds expire in 10 seconds
2. **Multiple Levels**: Be prepared to handle both Level 1 and Level 3 authentication
3. **Network Access**: Must be connected to the CarHackingCTF WiFi network
4. **Multi-Stage**: This is likely the first stage of a multi-ECU challenge

## Next Steps

Once initial authentication is successful:
1. Explore additional ECU endpoints
2. Analyze CAN bus communications
3. Identify engine control module access points
4. Extract final flag from engine ECU

The challenge simulates a realistic automotive attack chain from infotainment system compromise to critical engine control access.