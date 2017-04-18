type nat is (int x) where x >= 0

method f(any v) -> (int r)
ensures r >= 0:
    //
    if v is &nat:
        return (*v) + 1
    //
    return 0

public export method test():
    int result = f(new 1)
    assume result == 2
    //
    result = f(new -1)
    assume result == 0
