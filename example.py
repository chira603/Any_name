# Input the first number
x = int(input("Enter the first number: "))

# Input the second number
y = int(input("Enter the second number: "))

# Perform addition
addition = x + y
print("Addition:")
print(x, "+", y, "=", addition)
print()

# Perform subtraction (x - y)
subtraction = x - y
print("Subtraction:")
print(x, "-", y, "=", subtraction)
print()

# Perform subtraction (y - x)
reverse_subtraction = y - x
print("Reverse Subtraction:")
print(y, "-", x, "=", reverse_subtraction)
print()

# Perform multiplication
multiplication = x * y
print("Multiplication:")
print(x, "*", y, "=", multiplication)
print()

# Perform division (x / y)
if y != 0:
    division = x / y
    print("Division:")
    print(x, "/", y, "=", division)
else:
    print("Division by zero is not allowed.")
print()

# Perform division (y / x)
if x != 0:
    reverse_division = y / x
    print("Reverse Division:")
    print(y, "/", x, "=", reverse_division)
else:
    print("Division by zero is not allowed.")
print()

# Perform modulus
if y != 0:
    modulus = x % y
    print("Modulus:")
    print(x, "%", y, "=", modulus)
else:
    print("Modulus by zero is not allowed.")
print()

# Print a conclusion message
print("All operations completed successfully!")
