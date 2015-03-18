import whiley.lang.System
import print from whiley.lang.System

constant constants is [10000000b, 10000001b, 10000010b, 10000011b, 10000100b, 10000101b, 10000110b, 10000111b, 10001000b, 10001001b, 10001010b, 10001011b, 10001100b, 10001101b, 10001110b, 10001111b, 10010000b, 10010001b, 10010010b, 10010011b, 10010100b, 10010101b, 10010110b, 10010111b, 10011000b, 10011001b, 10011010b, 10011011b, 10011100b, 10011101b, 10011110b, 10011111b, 10100000b, 10100001b, 10100010b, 10100011b, 10100100b, 10100101b, 10100110b, 10100111b, 10101000b, 10101001b, 10101010b, 10101011b, 10101100b, 10101101b, 10101110b, 10101111b, 10110000b, 10110001b, 10110010b, 10110011b, 10110100b, 10110101b, 10110110b, 10110111b, 10111000b, 10111001b, 10111010b, 10111011b, 10111100b, 10111101b, 10111110b, 10111111b, 11000000b, 11000001b, 11000010b, 11000011b, 11000100b, 11000101b, 11000110b, 11000111b, 11001000b, 11001001b, 11001010b, 11001011b, 11001100b, 11001101b, 11001110b, 11001111b, 11010000b, 11010001b, 11010010b, 11010011b, 11010100b, 11010101b, 11010110b, 11010111b, 11011000b, 11011001b, 11011010b, 11011011b, 11011100b, 11011101b, 11011110b, 11011111b, 11100000b, 11100001b, 11100010b, 11100011b, 11100100b, 11100101b, 11100110b, 11100111b, 11101000b, 11101001b, 11101010b, 11101011b, 11101100b, 11101101b, 11101110b, 11101111b, 11110000b, 11110001b, 11110010b, 11110011b, 11110100b, 11110101b, 11110110b, 11110111b, 11111000b, 11111001b, 11111010b, 11111011b, 11111100b, 11111101b, 11111110b, 11111111b, 00000000b, 00000001b, 00000010b, 00000011b, 00000100b, 00000101b, 00000110b, 00000111b, 00001000b, 00001001b, 00001010b, 00001011b, 00001100b, 00001101b, 00001110b, 00001111b, 00010000b, 00010001b, 00010010b, 00010011b, 00010100b, 00010101b, 00010110b, 00010111b, 00011000b, 00011001b, 00011010b, 00011011b, 00011100b, 00011101b, 00011110b, 00011111b, 00100000b, 00100001b, 00100010b, 00100011b, 00100100b, 00100101b, 00100110b, 00100111b, 00101000b, 00101001b, 00101010b, 00101011b, 00101100b, 00101101b, 00101110b, 00101111b, 00110000b, 00110001b, 00110010b, 00110011b, 00110100b, 00110101b, 00110110b, 00110111b, 00111000b, 00111001b, 00111010b, 00111011b, 00111100b, 00111101b, 00111110b, 00111111b, 01000000b, 01000001b, 01000010b, 01000011b, 01000100b, 01000101b, 01000110b, 01000111b, 01001000b, 01001001b, 01001010b, 01001011b, 01001100b, 01001101b, 01001110b, 01001111b, 01010000b, 01010001b, 01010010b, 01010011b, 01010100b, 01010101b, 01010110b, 01010111b, 01011000b, 01011001b, 01011010b, 01011011b, 01011100b, 01011101b, 01011110b, 01011111b, 01100000b, 01100001b, 01100010b, 01100011b, 01100100b, 01100101b, 01100110b, 01100111b, 01101000b, 01101001b, 01101010b, 01101011b, 01101100b, 01101101b, 01101110b, 01101111b, 01110000b, 01110001b, 01110010b, 01110011b, 01110100b, 01110101b, 01110110b, 01110111b, 01111000b, 01111001b, 01111010b, 01111011b, 01111100b, 01111101b, 01111110b]

public method main(System.Console sys) -> void:
    for i in constants:
        for j in constants:
            sys.out.print_s(Any.toString(i) ++ " & ")
            sys.out.print_s(Any.toString(j) ++ " = ")
            sys.out.println_s(Any.toString(i & j))
