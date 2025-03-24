import os
import time
import json
import pytest
from pathlib import Path

RESULTS_DIR = "parallel_results"
JSON_RESULTS_FILE = os.path.join(RESULTS_DIR, "parallel_test_results.json")

# Ensure results directory exists
os.makedirs(RESULTS_DIR, exist_ok=True)

def run_tests(n_process, n_thread, dist_mode, repetition):
    """Run tests with given configuration and return execution time and failed tests."""
    xml_file = os.path.join(RESULTS_DIR, f"junit_n{n_process}_threads{n_thread}_dist{dist_mode}_rep{repetition}.xml")

    pytest_args = [
        "-n", str(n_process),
        "--parallel-threads", str(n_thread),
        f"--dist={dist_mode}",
        "--disable-warnings",
        "--maxfail=1",
        "--cov-context=test",
        "--junitxml", xml_file
    ]

    print(f"Running command: pytest {' '.join(pytest_args)} tests")
    
    start_time = time.time()
    pytest.main(pytest_args)
    exec_time = time.time() - start_time

    failed_tests = []
    
    # Check if XML file exists before parsing
    if os.path.exists(xml_file):
        try:
            from xml.etree import ElementTree as ET
            tree = ET.parse(xml_file)
            root = tree.getroot()
            for testcase in root.findall(".//testcase"):
                if testcase.find("failure") is not None:
                    failed_tests.append(testcase.attrib["name"])
        except Exception as e:
            print(f"Error parsing XML {xml_file}: {e}")
    else:
        print(f"Warning: XML report {xml_file} was not generated!")

    return exec_time, failed_tests

def main():
    configurations = [
        (1, 1, "load"),
        (1, 1, "no"),
        (1, "auto", "load"),
        (1, "auto", "no"),
    ]
    
    results = {}

    for n_process, n_thread, dist_mode in configurations:
        key = f"n={n_process}, threads={n_thread}, dist={dist_mode}"
        results[key] = {
            "repetition_times": [],
            "failures_per_repetition": [],
            "flaky_tests": {}
        }

        print(f"\nRunning tests with configuration: n={n_process}, threads={n_thread}, dist={dist_mode}")

        for rep in range(1, 4):
            print(f"  Repetition {rep}/3...")
            exec_time, failed_tests = run_tests(n_process, n_thread, dist_mode, rep)
            results[key]["repetition_times"].append(exec_time)
            results[key]["failures_per_repetition"].append(failed_tests)

        results[key]["average_execution_time"] = sum(results[key]["repetition_times"]) / 3

        # Identify flaky tests
        all_failures = [test for rep_failures in results[key]["failures_per_repetition"] for test in rep_failures]
        results[key]["flaky_tests"] = {test: all_failures.count(test) for test in set(all_failures)}

    # Save results to JSON
    with open(JSON_RESULTS_FILE, "w") as f:
        json.dump(results, f, indent=2)

    print(f"\nResults saved to {JSON_RESULTS_FILE}")

if __name__ == "__main__":
    main()
