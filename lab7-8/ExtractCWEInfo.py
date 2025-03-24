import pandas as pd
import os

def extract_cwe_info(csv_file):
    # Read the CSV file
    df = pd.read_csv(csv_file)

    # Extract and aggregate CWEs from the 'Unique CWEs' column
    cwe_set = set()
    for cwe_list in df['Unique CWEs']:
        if pd.notna(cwe_list):
            cwe_set.update(cwe_list.split(';'))

    # Format the CWE list for output
    cwe_list = sorted([cwe.split('/')[-1] for cwe in cwe_set if cwe.startswith("https://cwe.mitre.org/data/definitions/")])
    return cwe_list

def main():
    # Directory containing the CSV files
    csv_dir = "/home/chirag/Education/STT/lab7-8"

    # List of CSV files to process
    csv_files = [
        "bandit_analysis_matplotlib.csv",
        "bandit_analysis_alluxio.csv",
        "bandit_analysis_aiohttp.csv"
    ]

    # Process each CSV file and extract CWE information
    for csv_file in csv_files:
        cwe_list = extract_cwe_info(os.path.join(csv_dir, csv_file))
        repo_name = os.path.basename(csv_file).split('_')[2].split('.')[0].capitalize()
        print(f"--> All the commits in the {repo_name} Repository contain the following CWEs: {', '.join(cwe_list)}.")

if __name__ == "__main__":
    main()
