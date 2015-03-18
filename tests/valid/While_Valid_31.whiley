function indexOf(ASCII.string items, ASCII.char ch) -> (int r)
ensures r == |items| || items[r] == ch:
    //
    int i = 0
    //
    while i < |items| && items[i] != ch
    where 0 <= i && i <= |items|:
        i = i + 1
    //
    return i

method main(System.Console console):
    for c in "hello world":
        console.out.println_s("indexOf(\"hello world\",'" ++ [c] ++ "') = " ++ Any.toString(indexOf("hello world",c)))

