import re
with open("rust/src/lib.rs", "r") as f:
    content = f.read()

# Let's print out what we can. Let's just use `grep` on cargo.
