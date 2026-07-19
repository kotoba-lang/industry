# KPI Sensor Report — 2026-05-17

| Status | KPI | Value | Threshold | Evidence |
|---|---|---|---|---|
| 🟢 | `physics.commits` | 6 | >= 4/30d | git log --since=2026-04-17 |
| ⚪ | `paidy.overdue` | unknown | = 0 | Gmail センサが未起動。phase2_kpi_sensors.py --collect で取得 |
| ⚪ | `lingling.brief_last_seen` | unknown | <= 30 days | Gmail センサ未起動 |
| ⚪ | `aishi.disclosure` | unknown | received & shared | 未起動 |
| ⚪ | `health.clinic_visit` | unknown | 1+/month | 未起動 |
| ⚪ | `nodoka.time_with` | unknown | >= 8h/week | 未起動 |

## Pregel 反映用 state パッチ

```json
{
  "physics.commits": {
    "value": 6,
    "status": "green"
  },
  "paidy.overdue": {
    "value": "unknown",
    "status": "unknown"
  },
  "lingling.brief_last_seen": {
    "value": "unknown",
    "status": "unknown"
  },
  "aishi.disclosure": {
    "value": "unknown",
    "status": "unknown"
  },
  "health.clinic_visit": {
    "value": "unknown",
    "status": "unknown"
  },
  "nodoka.time_with": {
    "value": "unknown",
    "status": "unknown"
  }
}
```