#!/bin/bash

OUTPUT_FILE="bandit_analysis.csv"

# Initialize CSV with headers
echo "Commit,High Confidence,Medium Confidence,Low Confidence,High Severity,Medium Severity,Low Severity,Unique CWEs" > $OUTPUT_FILE

# Process each commit hash from commits.txt
while read commit; do
    echo "Analyzing commit: $commit"
    
    # Checkout the commit
    git checkout "$commit" --quiet

    # Run Bandit and capture both stdout and stderr
    bandit_raw=$(bandit -r . --exclude .git -f json 2>&1)
    
    # Extract the JSON portion (from the first '{' onward)
    bandit_output=$(echo "$bandit_raw" | sed -n '/^{/,$p')

    # Validate JSON output. If invalid, skip this commit.
    if ! echo "$bandit_output" | jq empty 2>/dev/null; then
        echo "Warning: Skipping commit $commit due to invalid JSON output from Bandit."
        continue
    fi

    # Extract counts for confidence levels
    high_conf=$(echo "$bandit_output" | jq '[.results[] | select(.issue_confidence == "HIGH")] | length')
    med_conf=$(echo "$bandit_output" | jq '[.results[] | select(.issue_confidence == "MEDIUM")] | length')
    low_conf=$(echo "$bandit_output" | jq '[.results[] | select(.issue_confidence == "LOW")] | length')

    # Extract counts for severity levels
    high_sev=$(echo "$bandit_output" | jq '[.results[] | select(.issue_severity == "HIGH")] | length')
    med_sev=$(echo "$bandit_output" | jq '[.results[] | select(.issue_severity == "MEDIUM")] | length')
    low_sev=$(echo "$bandit_output" | jq '[.results[] | select(.issue_severity == "LOW")] | length')

    # Extract unique CWE ids using the "issue_cwe.id" field.
    # If "issue_cwe.id" is null then it will be set to "null".
    unique_cwe_ids=$(echo "$bandit_output" | jq -r '[.results[].issue_cwe.id // "null"] | unique | .[]')
    links=""

    # For each unique CWE id, produce the appropriate URL.
    for id in $unique_cwe_ids; do
        if [ "$id" = "null" ]; then
            link="https://cwe.mitre.org/"
        else
            link="https://cwe.mitre.org/data/definitions/${id}.html"
        fi

        if [ -z "$links" ]; then
            links="$link"
        else
            links="$links;$link"
        fi
    done

    # Append the results to the CSV file
    echo "$commit,$high_conf,$med_conf,$low_conf,$high_sev,$med_sev,$low_sev,$links" >> $OUTPUT_FILE

done < commits.txt

# Switch back to the main branch when finished
git checkout main
echo "Analysis completed. Results saved in $OUTPUT_FILE."

