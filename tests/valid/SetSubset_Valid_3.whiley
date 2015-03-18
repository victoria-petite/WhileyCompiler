import whiley.lang.System

function f({int} xs, {int} ys) -> ASCII.string:
    if xs ⊂ ys:
        return "XS IS A SUBSET"
    else:
        return "FAILED"

function g({int} xs, {int} ys) -> ASCII.string:
    return f(xs, ys)

method main(System.Console sys) -> void:
    sys.out.println_s(g({1, 2, 3}, {1, 2, 3}))
    sys.out.println_s(g({1, 2}, {1, 2, 3}))
    sys.out.println_s(g({1}, {1, 2, 3}))
