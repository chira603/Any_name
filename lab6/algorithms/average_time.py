import subprocess
import os
import time

# Function to run pytest and capture the execution time
def run_tests(repetitions=5, json_output='result.json'):
    results = []
    
    for i in range(repetitions):
        print(f"Running tests: Attempt {i + 1}/{repetitions}")
        start_time = time.time()
        
        # Run pytest and capture the results in JSON format using the --json option
        result = subprocess.run(
            ['pytest', '--json=' + json_output, '--cov'],  # Collecting coverage info
            stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        
        end_time = time.time()
        execution_time = end_time - start_time
        print(f"Execution time for this run: {execution_time} seconds")

        results.append({
            'execution_time': execution_time
        })
    
    return results

# Function to calculate the average execution time from multiple runs
def calculate_average_time(results):
    successful_runs = [run['execution_time'] for run in results]
    avg_execution_time = sum(successful_runs) / len(successful_runs) if successful_runs else 0
    return avg_execution_time

# Main function to execute the test suite 5 times and calculate the average execution time
def main():
    print("\nStep 2: Running the cleaned test suite 5 times to calculate the average execution time.")
    repetitions = 5
    results = run_tests(repetitions=repetitions)
    
    # Calculate the average execution time for sequential test runs
    avg_execution_time = calculate_average_time(results)
    print(f"\nAverage execution time for 5 cleaned test runs: {avg_execution_time} seconds")

if __name__ == "__main__":
    main()
