import whiley.lang.System

type cr1nat is int

function f(cr1nat x) -> ASCII.string:
    int y = x
    return Any.toString(y)

method main(System.Console sys) -> void:
    sys.out.println_s(f(9))
