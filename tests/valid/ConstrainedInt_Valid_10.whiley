import whiley.lang.System

type nat is (int x) where x > 0

type num is (int x) where x in {1, 2, 3, 4}

function f(num x) -> ASCII.string:
    int y = x
    return Any.toString(y)

function g(int x, nat z) -> ASCII.string
requires ((x == 1) || (x == 2)) && (z in {1, 2, 3, x}):
    return f(z)

method main(System.Console sys) -> void:
    sys.out.println_s(g(1, 3))
