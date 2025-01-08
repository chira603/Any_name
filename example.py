"""
This script performs basic arithmetic operations (addition, subtraction, multiplication, 
division, and modulus) between two user-provided numbers. It also handles division 
and modulus by zero cases gracefully.
"""

# Input numbers
first_number = int(input("Enter the first number: "))
second_number = int(input("Enter the second number: "))

# Perform addition
addition = first_number + second_number
print(f"Addition: {first_number} + {second_number} = {addition}")

# Perform subtraction
subtraction = first_number - second_number
print(f"Subtraction: {first_number} - {second_number} = {subtraction}")

# Perform multiplication
multiplication = first_number * second_number
print(f"Multiplication: {first_number} * {second_number} = {multiplication}")

# Perform division
if second_number != 0:
    division = first_number / second_number
    print(f"Division: {first_number} / {second_number} = {division:.2f}")
else:
    print("Division: Division by zero is not allowed.")

# Perform modulus
if second_number != 0:
    modulus = first_number % second_number
    print(f"Modulus: {first_number} % {second_number} = {modulus}")
else:
    print("Modulus: Modulus by zero is not allowed.")

# End of script
print("Arithmetic operations completed.")
