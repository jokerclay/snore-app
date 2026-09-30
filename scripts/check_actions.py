import urllib.request
import json
import sys

def main():
    url = "https://api.github.com/repos/jokerclay/snore-app/actions/runs"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        data = json.loads(urllib.request.urlopen(req).read().decode('utf-8'))
        runs = data.get("workflow_runs", [])
        print(f"Total runs found: {len(runs)}")
        for r in runs[:5]:
            run_id = r["id"]
            status = r["status"]
            conclusion = r["conclusion"]
            msg = r["head_commit"]["message"].split("\n")[0]
            print(f"Run {run_id} | Status: {status} | Conclusion: {conclusion} | Commit: {msg}")

            # Check jobs for this run
            jobs_url = f"https://api.github.com/repos/jokerclay/snore-app/actions/runs/{run_id}/jobs"
            j_req = urllib.request.Request(jobs_url, headers={"User-Agent": "Mozilla/5.0"})
            j_data = json.loads(urllib.request.urlopen(j_req).read().decode('utf-8'))
            for j in j_data.get("jobs", []):
                print(f"  Job: {j['name']} ({j['status']}, {j['conclusion']})")
                for s in j.get("steps", []):
                    if s.get("conclusion") == "failure":
                        print(f"    FAILED STEP: {s['name']}")
    except Exception as e:
        print("Error:", e)

if __name__ == '__main__':
    main()
