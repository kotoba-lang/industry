version = "0.1"

task "run" {
  description = "Run system_thinking analysis inside python container"
  image = "python:3.11"
  workdir = "/work"
  mounts = [
    { host = ".", target = "/work" }
  ]
  env = {}
  command = [
    "bash", "-lc",
    "python -m pip install --upgrade pip && \"
    "python -m pip install -r resources/system_thinking/requirements.txt || true && \"
    "python resources/system_thinking/system_thinking.py resources/system_thinking/edges.csv"
  ]
}
