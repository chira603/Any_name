import subprocess
import json
import time
import os

# Function to run pytest multiple times and capture the results
def run_tests(repetitions=10):
    results = []
    
    for i in range(repetitions):
        print(f"Running tests: Attempt {i + 1}/{repetitions}")
        start_time = time.time()
        
        # Run pytest and capture the results in JSON format using the --json option
        result = subprocess.run(
            ['pytest', '--json=result.json', '--cov'],  # Collecting coverage info
            stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        
        end_time = time.time()
        execution_time = end_time - start_time
        print(f"Execution time for this run: {execution_time} seconds")
        
        # Parse the JSON result to extract the test outcomes
        if os.path.exists('result.json'):
            with open('result.json') as f:
                result_data = json.load(f)
                
                # Check if the 'report' and 'tests' keys exist in the JSON
                if 'report' in result_data and 'tests' in result_data['report']:
                    print("Test results loaded successfully.")
                else:
                    print("Error: 'tests' or 'report' not found in the JSON result.")
                    continue  # Skip this run if required keys are missing
                
        else:
            print("Error: result.json file not generated.")
            continue  # Skip this run if JSON file is not generated
        
        results.append({
            'execution_time': execution_time,
            'result_data': result_data
        })
    
    return results

# Function to analyze the test results and find flaky and failing tests
def analyze_test_results(results):
    all_tests = {}
    flaky_tests = {}
    failing_tests = {}
    
    for run in results:
        for test in run['result_data']['report']['tests']:  # Correctly accessing 'tests' within 'report'
            test_name = test['name']
            test_status = test['outcome']
            
            if test_name not in all_tests:
                all_tests[test_name] = {'statuses': []}
            
            all_tests[test_name]['statuses'].append(test_status)
    
    for test_name, data in all_tests.items():
        # Check if the test is flaky (passed in some runs, failed in others)
        if 'failed' in data['statuses'] and 'passed' in data['statuses']:
            flaky_tests[test_name] = data['statuses']
        
        # Check if the test is completely failing
        elif 'failed' in data['statuses']:
            failing_tests[test_name] = data['statuses']
    
    return flaky_tests, failing_tests

# Main function to run the tests, analyze results, and print them
def main():
    repetitions = 10
    results = run_tests(repetitions)
    
    print("Analyzing test results...")
    flaky_tests, failing_tests = analyze_test_results(results)
    
    print("\nFlaky Tests (pass in some runs, fail in others):")
    for test_name, statuses in flaky_tests.items():
        print(f"  - {test_name}: {statuses}")
    
    print("\nFailing Tests (fail in all runs):")
    for test_name, statuses in failing_tests.items():
        print(f"  - {test_name}: {statuses}")
    
    # Calculate average execution time for sequential test runs (after eliminating flaky/failing tests)
    successful_runs = [run['execution_time'] for run in results if run['result_data']]
    avg_execution_time = sum(successful_runs) / len(successful_runs) if successful_runs else 0
    print(f"\nAverage execution time for {repetitions} test runs: {avg_execution_time} seconds")

if __name__ == "__main__":
    main()
